#import <AppKit/AppKit.h>
#include "../macos_presentation_identity.h"

__attribute__((visibility("default")))
void rigel_install_dock_identity(const char* bundle_path) {
    if (!bundle_path) return;
    NSString* bundlePath = [NSString stringWithUTF8String:bundle_path];
    // The Weather preset lives beside dock_identity.dylib in the package
    // root; BigM's custom-icon environment still overrides it.
    presentation_install_preset(bundlePath, @"Rigel", @"Google Chrome", @"weather");
    presentation_apply([NSApplication sharedApplication]);
}
