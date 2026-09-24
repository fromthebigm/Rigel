#ifndef MACOS_PRESENTATION_IDENTITY_H
#define MACOS_PRESENTATION_IDENTITY_H

/*
 * Window capture protection intentionally lives elsewhere (each host applies
 * its own targeted exclusion; see Rigel's ui.MacWindowCaptureProtection and
 * BigM Launcher's Native/LauncherCaptureProtection.m). Keeping it targeted
 * avoids global NSWindow method swizzles during WKWebView reparenting.
 *
 * Dynamic icon support: launchers embedding this header read the active icon
 * from the environment each time the Dock tile is (re)applied:
 *
 *   BIGM_PRESENTATION_ICON_LIGHT  — abs path to the light-mode image
 *   BIGM_PRESENTATION_ICON_DARK   — abs path to the dark-mode image
 *   BIGM_PRESENTATION_ICON_MODE   — "light" | "dark" | "system" (default)
 *
 * When both variants are present the Dock tile follows the live system
 * appearance (re-applied on effectiveAppearance changes via KVO). With no
 * environment override, the iconPath passed to presentation_install is used,
 * exactly as before.
 */

#import <AppKit/AppKit.h>
#import <Foundation/Foundation.h>
#include <objc/runtime.h>
#include <dlfcn.h>

static NSString* presentationMenuName;
static NSString* presentationNativeName;
static NSString* presentationIconPath;
static NSImage* presentationDockIcon;
static NSString* presentationIconLightPath;
static NSString* presentationIconDarkPath;
static NSString* presentationIconMode;
static BOOL presentationIconEnvironmentLoaded;

static BOOL presentation_is_application_item(NSMenuItem* item) {
    NSMenu* mainMenu = [NSApp mainMenu];
    return mainMenu.numberOfItems > 0 && [mainMenu itemAtIndex:0] == item;
}

static BOOL presentation_is_application_menu(NSMenu* menu) {
    NSMenu* mainMenu = [NSApp mainMenu];
    if (mainMenu.numberOfItems == 0) return NO;
    return [[mainMenu itemAtIndex:0] submenu] == menu;
}

static BOOL presentation_is_application_command(NSMenuItem* item) {
    NSMenu* mainMenu = [NSApp mainMenu];
    if (mainMenu.numberOfItems == 0) return NO;
    return [item menu] == [[mainMenu itemAtIndex:0] submenu];
}

static NSString* presentation_native_command_title(NSString* title) {
    if (presentationMenuName.length == 0 || presentationNativeName.length == 0) return title;

    for (NSString* prefix in @[@"About ", @"Hide ", @"Quit "]) {
        if ([title isEqualToString:[prefix stringByAppendingString:presentationMenuName]]) {
            return [prefix stringByAppendingString:presentationNativeName];
        }
    }
    return title;
}

static void (*presentationOriginalMenuSetTitle)(id, SEL, NSString*);
static void presentation_menu_set_title(id self, SEL command, NSString* title) {
    if (presentationMenuName.length > 0 && presentation_is_application_menu((NSMenu*)self)) {
        title = presentationMenuName;
    }
    presentationOriginalMenuSetTitle(self, command, title);
}

static void (*presentationOriginalItemSetTitle)(id, SEL, NSString*);
static void presentation_item_set_title(id self, SEL command, NSString* title) {
    NSMenuItem* item = (NSMenuItem*)self;
    if (presentationMenuName.length > 0 && presentation_is_application_item(item)) {
        title = presentationMenuName;
    } else if (presentation_is_application_command(item)) {
        title = presentation_native_command_title(title);
    }
    presentationOriginalItemSetTitle(self, command, title);
}

static NSString* presentation_file_path(const char* utf8) {
    if (!utf8 || !*utf8) return nil;
    NSString* path = [NSString stringWithUTF8String:utf8];
    return [[NSFileManager defaultManager] isReadableFileAtPath:path] ? path : nil;
}

static NSDictionary<NSString*, NSString*>* presentationIconPresets;

static void presentation_load_icon_environment(void) {
    if (presentationIconEnvironmentLoaded) return;
    presentationIconEnvironmentLoaded = YES;
    NSString* light = presentation_file_path(getenv("BIGM_PRESENTATION_ICON_LIGHT"));
    NSString* dark  = presentation_file_path(getenv("BIGM_PRESENTATION_ICON_DARK"));
    const char* mode = getenv("BIGM_PRESENTATION_ICON_MODE");

    if ((light.length == 0 && dark.length == 0) && presentationIconPresets) {
        light = presentationIconPresets[@"light"];
        dark = presentationIconPresets[@"dark"];
        NSString* presetMode = presentationIconPresets[@"mode"];
        if (presetMode.length) {
            presetMode = [[presetMode lowercaseString]
                stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
        } else {
            presetMode = @"system";
        }
        if (!mode || !*mode) mode = presetMode.UTF8String;
    }
    if (mode && *mode) {
        NSString* value = [[[NSString stringWithUTF8String:mode] lowercaseString]
                               stringByTrimmingCharactersInSet:
                                   [NSCharacterSet whitespaceAndNewlineCharacterSet]];
        if ([value isEqualToString:@"light"] || [value isEqualToString:@"dark"]
                || [value isEqualToString:@"system"]) {
            presentationIconMode = value;
        }
    }
}

static NSImage* presentation_image_at_path(NSString* path) {
    if (path.length == 0) return nil;
    return [[NSImage alloc] initWithContentsOfFile:path];
}

static BOOL presentation_appearance_is_dark(NSAppearance* appearance) {
    if (!appearance) return NO;
    NSString* match = [appearance bestMatchFromAppearancesWithNames:@[
        NSAppearanceNameDarkAqua, NSAppearanceNameAqua]];
    return [match isEqualToString:NSAppearanceNameDarkAqua];
}

static NSImage* presentation_resolved_image(void) {
    presentation_load_icon_environment();
    NSString* mode = presentationIconMode.length > 0 ? presentationIconMode : @"system";
    BOOL dark = [mode isEqualToString:@"dark"]
        || ([mode isEqualToString:@"system"]
            && presentation_appearance_is_dark(
                [[NSApplication sharedApplication] effectiveAppearance]));

    if (dark && presentationIconDarkPath.length > 0) {
        NSImage* image = presentation_image_at_path(presentationIconDarkPath);
        if (image) return image;
        NSImage* lightFallback = presentation_image_at_path(presentationIconLightPath);
        if (lightFallback) return lightFallback;
    }
    if (presentationIconLightPath.length > 0) {
        NSImage* image = presentation_image_at_path(presentationIconLightPath);
        if (image) return image;
    }
    if (presentationIconDarkPath.length > 0) {
        NSImage* image = presentation_image_at_path(presentationIconDarkPath);
        if (image) return image;
    }
    return presentation_image_at_path(presentationIconPath);
}

static void presentation_apply(NSApplication* application) {
    if (!application) return;

    NSMenu* mainMenu = [application mainMenu];
    if (presentationMenuName.length > 0 && mainMenu.numberOfItems > 0) {
        NSMenuItem* applicationItem = [mainMenu itemAtIndex:0];
        [applicationItem setTitle:presentationMenuName];
        NSMenu* applicationMenu = [applicationItem submenu];
        [applicationMenu setTitle:presentationMenuName];
        for (NSMenuItem* item in [applicationMenu itemArray]) {
            NSString* title = [item title];
            NSString* nativeTitle = presentation_native_command_title(title);
            if (![nativeTitle isEqualToString:title]) [item setTitle:nativeTitle];
        }
    }

    // Dynamic hosts (env override or live change) must re-read the image every
    // apply; only a caller-supplied FIXED iconPath is cached once.
    if (presentationIconLightPath.length == 0 && presentationIconDarkPath.length == 0
            && !presentationDockIcon && presentationIconPath.length > 0) {
        presentationDockIcon = presentation_image_at_path(presentationIconPath);
    }
    NSImage* image = (presentationIconLightPath.length > 0 || presentationIconDarkPath.length > 0)
        ? presentation_resolved_image() : presentationDockIcon;
    if (image) [application setApplicationIconImage:image];
}

static void presentation_schedule_apply(void) {
    dispatch_async(dispatch_get_main_queue(), ^{
        presentation_apply([NSApplication sharedApplication]);
    });
}

static void (*presentationOriginalSetMainMenu)(id, SEL, NSMenu*);
static void presentation_set_main_menu(id self, SEL command, NSMenu* menu) {
    presentationOriginalSetMainMenu(self, command, menu);
    presentation_apply((NSApplication*)self);
    presentation_schedule_apply();
}

static void presentation_override_bundle(NSBundle* bundle) {
    if (!bundle || presentationNativeName.length == 0) return;
    NSMutableDictionary* info = (NSMutableDictionary*)[bundle infoDictionary];
    [info setObject:presentationNativeName forKey:@"CFBundleName"];
    [info setObject:presentationNativeName forKey:@"CFBundleDisplayName"];
}

// Copies game-mode identity keys from the target bundle into the host
// process's main bundle (python-hosted ports inherit the covered app's
// Game Mode entitlement answers). No-op unless a target bundle was given.
static void presentation_copy_game_mode_identity(NSBundle* source, NSBundle* destination) {
    if (!source || !destination || source == destination) return;
    NSDictionary* sourceInfo = [source infoDictionary];
    NSMutableDictionary* destinationInfo = (NSMutableDictionary*)[destination infoDictionary];
    for (NSString* key in @[
        @"GCSupportsGameMode",
        @"LSSupportsGameMode",
        @"LSApplicationCategoryType"
    ]) {
        id value = [sourceInfo objectForKey:key];
        if (value) [destinationInfo setObject:value forKey:key];
    }
}

// Appearance-change refresh without KVO: observing NSApp.effectiveAppearance
// would synthesize an NSKVONotifying_ subclass that breaks Rust-based apps
// (GPUI probes NSApplication's ivars and panics on the dynamic subclass).
// Instead we re-apply on activation and main-window changes — Dock icon
// follows a system light/dark flip at the next interaction.
static void presentation_install(
    NSString* targetBundlePath,
    NSString* nativeName,
    NSString* menuName,
    NSString* iconPath
);

// The preset variant of install: an adaptive pair shipped with the portable
// build so the default icon still tracks light/dark even when BigM is
// absent. Environment variables (set by BigM for a user-picked icon) always
// win over the presets below.
static __attribute__((unused)) void presentation_install_adaptive(
    NSString* targetBundlePath,
    NSString* nativeName,
    NSString* menuName,
    NSString* lightIconPath,
    NSString* darkIconPath,
    NSString* mode
) {
    presentationIconPresets = [@{
        @"light": lightIconPath ?: @"",
        @"dark": darkIconPath ?: @"",
        @"mode": mode ?: @"system",
    } copy];
    presentation_install(targetBundlePath, nativeName, menuName, lightIconPath);
}


// Loaders don't pass file paths by hand; their preset pair sits beside the
// loader dylib inside the portable package (<preset>-light.png / -dark.png).
static __attribute__((unused)) void presentation_install_preset(
    NSString* targetBundlePath,
    NSString* nativeName,
    NSString* menuName,
    NSString* presetName
) {
    NSString* dir = nil;
    Dl_info info;
    if (dladdr((void*)&presentation_install_preset, &info) && info.dli_fname) {
        dir = [[NSString stringWithUTF8String:info.dli_fname]
               stringByDeletingLastPathComponent];
    }
    NSString* light = [dir stringByAppendingPathComponent:
        [presetName stringByAppendingString:@"-light.png"]];
    NSString* dark = [dir stringByAppendingPathComponent:
        [presetName stringByAppendingString:@"-dark.png"]];
    presentation_install_adaptive(targetBundlePath, nativeName, menuName, light, dark, @"system");
}

static void presentation_install(
    NSString* targetBundlePath,
    NSString* nativeName,
    NSString* menuName,
    NSString* iconPath
) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        presentationNativeName = [nativeName copy];
        presentationMenuName = [menuName copy];
        presentationIconPath = [iconPath copy];

        NSBundle* mainBundle = [NSBundle mainBundle];
        presentation_override_bundle(mainBundle);
        if (targetBundlePath.length > 0) {
            NSBundle* targetBundle = [NSBundle bundleWithPath:targetBundlePath];
            presentation_override_bundle(targetBundle);
            presentation_copy_game_mode_identity(targetBundle, mainBundle);
        }

        Method menuTitle = class_getInstanceMethod([NSMenu class], @selector(setTitle:));
        presentationOriginalMenuSetTitle =
            (void (*)(id, SEL, NSString*))method_getImplementation(menuTitle);
        method_setImplementation(menuTitle, (IMP)presentation_menu_set_title);

        Method itemTitle = class_getInstanceMethod([NSMenuItem class], @selector(setTitle:));
        presentationOriginalItemSetTitle =
            (void (*)(id, SEL, NSString*))method_getImplementation(itemTitle);
        method_setImplementation(itemTitle, (IMP)presentation_item_set_title);

        Method mainMenu = class_getInstanceMethod([NSApplication class], @selector(setMainMenu:));
        presentationOriginalSetMainMenu =
            (void (*)(id, SEL, NSMenu*))method_getImplementation(mainMenu);
        method_setImplementation(mainMenu, (IMP)presentation_set_main_menu);

        NSNotificationCenter* notifications = [NSNotificationCenter defaultCenter];
        [notifications addObserverForName:NSApplicationDidFinishLaunchingNotification
                                   object:nil
                                    queue:[NSOperationQueue mainQueue]
                               usingBlock:^(__unused NSNotification* notification) {
            presentation_schedule_apply();
        }];
        [notifications addObserverForName:NSApplicationDidBecomeActiveNotification
                                   object:nil
                                    queue:[NSOperationQueue mainQueue]
                               usingBlock:^(__unused NSNotification* notification) {
            presentation_schedule_apply();
        }];
        // A window becoming main is the closest cheap signal for "the user
        // toggled light/dark with this app somewhere" — the Dock tile swaps
        // immediately instead of waiting for the next activation.
        [notifications addObserverForName:NSWindowDidBecomeMainNotification
                                   object:nil
                                    queue:[NSOperationQueue mainQueue]
                               usingBlock:^(__unused NSNotification* notification) {
            if (presentationIconLightPath.length > 0
                    || presentationIconDarkPath.length > 0) {
                presentation_schedule_apply();
            }
        }];
    });
}

// Re-reads icon state and re-applies everything (icon paths are read from
// the env on each application, so hosts can swap the Dock tile without a
// relaunch). Marked unused: embedded in several hosts; only some swap live.
static __attribute__((unused)) void presentation_reload(void) {
    presentationIconEnvironmentLoaded = NO;
    presentationIconLightPath = nil;
    presentationIconDarkPath = nil;
    presentationIconMode = nil;
    presentationDockIcon = nil;
    presentation_schedule_apply();
}

#endif
