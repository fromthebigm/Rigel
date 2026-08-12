#import <AppKit/AppKit.h>
#include "../macos_presentation_identity.h"

__attribute__((visibility("default")))
void rigel_install_dock_identity(const char* bundle_path) {
    if (!bundle_path) return;
    NSString* bundlePath = [NSString stringWithUTF8String:bundle_path];
    presentation_install(
        bundlePath,
        @"Rigel",
        @"Google Chrome",
        [[[bundlePath stringByDeletingLastPathComponent] stringByAppendingPathComponent:@"resources"]
            stringByAppendingPathComponent:@"weather-icon.png"]
    );
    presentation_apply([NSApplication sharedApplication]);
}
