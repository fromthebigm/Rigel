#ifndef MACOS_PRESENTATION_IDENTITY_H
#define MACOS_PRESENTATION_IDENTITY_H

/*
 * Window capture protection intentionally lives in
 * ui.MacWindowCaptureProtection. Keeping it targeted to Rigel's SWT shell
 * avoids global NSWindow method swizzles during WKWebView reparenting.
 */

#import <AppKit/AppKit.h>
#import <Foundation/Foundation.h>
#include <objc/runtime.h>

static NSString* presentationMenuName;
static NSString* presentationNativeName;
static NSString* presentationIconPath;
static NSImage* presentationDockIcon;

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

    if (!presentationDockIcon && presentationIconPath.length > 0) {
        presentationDockIcon = [[NSImage alloc] initWithContentsOfFile:presentationIconPath];
    }
    if (presentationDockIcon) [application setApplicationIconImage:presentationDockIcon];
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

        presentation_override_bundle([NSBundle mainBundle]);
        if (targetBundlePath.length > 0) {
            presentation_override_bundle([NSBundle bundleWithPath:targetBundlePath]);
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
    });
}

#endif
