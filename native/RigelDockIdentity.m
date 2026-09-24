#import <AppKit/AppKit.h>
#include <objc/runtime.h>
#include "../macos_presentation_identity.h"

// Stable storage identity. A bare java process resolves [NSBundle mainBundle]
// to the JDK vendor's launcher stub (com.azul.zulu.java, net.java.openjdk.java,
// ...), and WebKit keys EVERYTHING persistent on that identifier — the cookie
// jar (~/Library/HTTPStorages/<id>.binarycookies), localStorage, IndexedDB.
// The jar therefore flips whenever the bundled JDK vendor, JDK build, or
// fallback java changes, and every flip silently "forgets" all accounts.
// Redirecting mainBundle to the folder-owned Weather.app pins the identity to
// com.rigel.weather-host on every machine and every JDK — the same pattern
// Chiaki's dove_loader uses for its embedded app.
static NSString* g_identity_bundle_path;

static id (*rigel_orig_main_bundle)(id, SEL);
static id rigel_custom_main_bundle(id self, SEL _cmd) {
    if (g_identity_bundle_path.length > 0) {
        NSBundle* bundle = [NSBundle bundleWithPath:g_identity_bundle_path];
        if (bundle) return bundle;
    }
    return rigel_orig_main_bundle(self, _cmd);
}

__attribute__((visibility("default")))
void rigel_install_dock_identity(const char* bundle_path) {
    if (!bundle_path) return;
    NSString* bundlePath = [NSString stringWithUTF8String:bundle_path];
    g_identity_bundle_path = bundlePath;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        Method method = class_getClassMethod([NSBundle class], @selector(mainBundle));
        rigel_orig_main_bundle = (id (*)(id, SEL))method_getImplementation(method);
        method_setImplementation(method, (IMP)rigel_custom_main_bundle);
    });
    // The Weather preset lives beside dock_identity.dylib in the package
    // root; BigM's custom-icon environment still overrides it.
    presentation_install_preset(bundlePath, @"Rigel", @"Google Chrome", @"weather");
    presentation_apply([NSApplication sharedApplication]);
}
