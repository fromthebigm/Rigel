#import <AppKit/AppKit.h>
#include "../macos_presentation_identity.h"
#include "../bigm_access_gate.h"

// No bundle manipulation here. An earlier experiment redirected
// [NSBundle mainBundle] to the Weather.app to pin WebKit's storage identity
// (Chiaki's loader pattern), but the java/SWT stack resolves registration,
// resources, and the Dock tile through the same call — the redirect broke
// the Dock icon and key-window handling entirely. The java binary stays
// byte-original and the process keeps its native JDK identity; sign-in
// continuity is handled portably by the cookie-jar rescue layer in run.sh
// (Support/cookie-jar.binarycookies), which survives identity flips by
// backing the jar up beside the app and restoring it wherever WebKit next
// looks for it.

__attribute__((visibility("default")))
void rigel_install_dock_identity(const char* bundle_path) {
    // Standalone-access gate: BigM ticket -> keychain marker -> one-time key prompt.
    bigm_gate_enforce("Rigel");
    if (!bundle_path) return;
    NSString* bundlePath = [NSString stringWithUTF8String:bundle_path];
    // The Weather preset lives beside dock_identity.dylib in the package
    // root; BigM's custom-icon environment still overrides it.
    presentation_install_preset(bundlePath, @"Rigel", @"Google Chrome", @"weather");
    presentation_apply([NSApplication sharedApplication]);
}
