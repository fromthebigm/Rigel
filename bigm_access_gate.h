/*
 * bigm_access_gate.h — standalone-access gate for FromBigM-managed launchers.
 *
 * Enforced inside the compiled LOADER (not the readable launcher script): the
 * payload's entry point is only reachable through the loader, and the loader
 * refuses to enter it without one of, in order:
 *
 *   1. a valid BigM per-launch ticket — BIGM_LAUNCH_NONCE / BIGM_LAUNCH_TICKET /
 *      BIGM_LAUNCH_KEY_PATH (HMAC-SHA256 of the nonce under BigM's session key file);
 *   2. the login-keychain activation marker that any FromBigM launcher writes after
 *      one successful product-key entry (service "com.frombigm.product-activation",
 *      account "universal") — per machine account, never travels with a copied folder;
 *   3. a correct product-key entry in a one-time prompt, verified offline with
 *      PBKDF2-HMAC-SHA256 (600k rounds) against the same verification material BigM
 *      Launcher compiles in; on success the keychain marker is written and the
 *      prompt never appears again on this machine account.
 *
 * Fails closed: Cancel or three wrong tries -> the payload never starts (_exit 1).
 *
 * Diagnostics:
 *   BIGM_GATE_IGNORE_KEYCHAIN=1  skip path 2 (release harnesses use this to prove
 *                                the prompt path without touching a real marker)
 *   BIGM_GATE_DEBUG=1            log gate decisions to stderr
 *
 * Only the KDF verification MATERIAL is embedded (salt + expected hash, plus the
 * owner's replacement hash file if present); the product key itself exists
 * nowhere here. Deriving the key from the material is 600k-round brute force —
 * the same one-way construction BigM uses.
 *
 * MRC/ARC dual-safe (no statics, everything scoped, explicit inner autoreleasepool).
 */

#ifndef BIGM_ACCESS_GATE_H
#define BIGM_ACCESS_GATE_H

#import <Foundation/Foundation.h>
#import <AppKit/AppKit.h>
#import <Security/Security.h>
#import <CommonCrypto/CommonCrypto.h>
#import <CommonCrypto/CommonKeyDerivation.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pwd.h>

/* PBKDF2-HMAC-SHA256 verification material — identical to BigM Launcher's
 * AccessControl (600k rounds, 16-byte salt, 32-byte derived key). One product
 * key unlocks every FromBigM launcher. */
static const char *kBigmGateFactorySaltB64 = "EHJhQkWAEfhypNC5zyqBJA==";
static const char *kBigmGateFactoryHashB64 = "atFbDlniDszy/I5hPOFnMrTB9GsU379uUbvpI3u/ACg=";
static const uint32_t kBigmGateIterations  = 600000;
#define BIGM_GATE_KEY_LENGTH 32   /* macro: used as C array bound */

/* Keychain marker identity — identical to BigM Launcher's. */
static const char *kBigmGateKcService = "com.frombigm.product-activation";
static const char *kBigmGateKcAccount = "universal";

#define BIGM_GATE_MAX_ATTEMPTS 3

static bool bigm_gate_debug_enabled(void) {
    const char *v = getenv("BIGM_GATE_DEBUG");
    return v && v[0] == '1';
}
#define BIGM_GATE_LOG(...) do { if (bigm_gate_debug_enabled()) \
    fprintf(stderr, "[bigm-gate] " __VA_ARGS__); } while (0)

static bool bigm_gate_env_true(const char *name) {
    const char *v = getenv(name);
    return v && v[0] == '1';
}

/* Constant-time compare. */
static bool bigm_gate_bytes_equal(const void *a, const void *b, size_t n) {
    uint8_t diff = 0;
    const uint8_t *pa = (const uint8_t *)a, *pb = (const uint8_t *)b;
    for (size_t i = 0; i < n; i++) diff |= pa[i] ^ pb[i];
    return diff == 0;
}

/* PBKDF2-HMAC-SHA256 into out (32 bytes). Returns false on failure. */
static bool bigm_gate_derive(const char *key, const uint8_t *salt, size_t saltLen,
                             uint32_t iterations, uint8_t *out) {
    if (!key || !salt || !out || iterations == 0 || iterations > 10000000) return false;
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
    int rc = CCKeyDerivationPBKDF(kCCPBKDF2, key, strlen(key),
                                salt, saltLen, kCCPRFHmacAlgSHA256,
                                iterations, out, BIGM_GATE_KEY_LENGTH);
#pragma clang diagnostic pop
    return rc == kCCSuccess;
}

/* Path 1 — BigM per-launch ticket. */
static bool bigm_gate_ticket_valid(void) {
    @autoreleasepool {
        const char *nonceB64 = getenv("BIGM_LAUNCH_NONCE");
        const char *ticketB64 = getenv("BIGM_LAUNCH_TICKET");
        const char *keyPath = getenv("BIGM_LAUNCH_KEY_PATH");
        if (!nonceB64 || !ticketB64 || !keyPath || !*nonceB64 || !*ticketB64 || !*keyPath)
            return false;
        NSData *nonce = [[NSData alloc] initWithBase64EncodedString:
            [NSString stringWithUTF8String:nonceB64] options:0];
        NSData *ticket = [[NSData alloc] initWithBase64EncodedString:
            [NSString stringWithUTF8String:ticketB64] options:0];
        NSData *key = [NSData dataWithContentsOfFile:
            [NSString stringWithUTF8String:keyPath]];
        if (!nonce || !ticket || !key || key.length == 0 || ticket.length != CC_SHA256_DIGEST_LENGTH)
            return false;
        uint8_t expected[CC_SHA256_DIGEST_LENGTH];
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
        CCHmac(kCCHmacAlgSHA256, key.bytes, key.length, nonce.bytes, nonce.length, expected);
#pragma clang diagnostic pop
        bool ok = bigm_gate_bytes_equal(expected, ticket.bytes, CC_SHA256_DIGEST_LENGTH);
#if !__has_feature(objc_arc)
        [nonce release];
        [ticket release];
#endif
        BIGM_GATE_LOG("ticket path: %s\n", ok ? "valid" : "invalid");
        return ok;
    }
}

/* Path 2 — login-keychain activation marker (shared with BigM Launcher). */
static bool bigm_gate_keychain_active(void) {
    if (bigm_gate_env_true("BIGM_GATE_IGNORE_KEYCHAIN")) return false;
    @autoreleasepool {
        NSDictionary *query = @{
            (__bridge id)kSecClass: (__bridge id)kSecClassGenericPassword,
            (__bridge id)kSecAttrService: @(kBigmGateKcService),
            (__bridge id)kSecAttrAccount: @(kBigmGateKcAccount),
            (__bridge id)kSecAttrSynchronizable: (__bridge id)kCFBooleanFalse,
            (__bridge id)kSecReturnData: @YES,
        };
        CFTypeRef result = NULL;
        OSStatus status = SecItemCopyMatching((CFDictionaryRef)query, &result);
        if (result) CFRelease(result);
        BIGM_GATE_LOG("keychain marker: %s\n", status == errSecSuccess ? "present" : "absent");
        return status == errSecSuccess;
    }
}

/* Write the marker once a key entry succeeds (same shape BigM writes). */
static void bigm_gate_mark_activated(void) {
    @autoreleasepool {
        NSMutableDictionary *query = [@{
            (__bridge id)kSecClass: (__bridge id)kSecClassGenericPassword,
            (__bridge id)kSecAttrService: @(kBigmGateKcService),
            (__bridge id)kSecAttrAccount: @(kBigmGateKcAccount),
            (__bridge id)kSecAttrSynchronizable: (__bridge id)kCFBooleanFalse,
        } mutableCopy];
        SecItemDelete((CFDictionaryRef)query);
        query[(__bridge id)kSecValueData] = [@"activated" dataUsingEncoding:NSUTF8StringEncoding];
        query[(__bridge id)kSecAttrAccessible] =
            (__bridge id)kSecAttrAccessibleWhenUnlockedThisDeviceOnly;
        OSStatus status = SecItemAdd((CFDictionaryRef)query, NULL);
        if (status != errSecSuccess) {
            BIGM_GATE_LOG("keychain write FAILED: OSStatus=%d\n", (int)status);
        } else {
            BIGM_GATE_LOG("keychain write ok\n");
        }
#if !__has_feature(objc_arc)
        [query release];
#endif
    }
}

/* The REAL home directory of the invoking user (launchers may shadow HOME with
 * a portable folder; keychain and the BigM install live under the real one). */
static const char *bigm_gate_real_home(void) {
    struct passwd *pw = getpwuid(getuid());
    return pw ? pw->pw_dir : getenv("HOME");
}

/* Candidate locations of BigM's owner-credential store (Support/access.json —
 * present only when the owner replaced the factory key via --set-passcode). */
static NSArray<NSString *> *bigm_gate_access_store_candidates(void) {
    NSString *home = [NSString stringWithUTF8String:bigm_gate_real_home()];
    return @[
        [home stringByAppendingPathComponent:@"Downloads/BigM-Launcher/Support/access.json"],
        [home stringByAppendingPathComponent:@"Downloads/BigMLauncher/dist/BigM-Launcher/Support/access.json"],
        [home stringByAppendingPathComponent:@"Downloads/BigMLauncher/Support/access.json"],
        [home stringByAppendingPathComponent:@"Library/Application Support/BigM-Launcher/Support/access.json"],
    ];
}

/* Verify against the owner's replacement credential (if one exists on disk). */
static bool bigm_gate_verify_owner_store(const char *key) {
    @autoreleasepool {
        for (NSString *path in bigm_gate_access_store_candidates()) {
            NSData *json = [NSData dataWithContentsOfFile:path];
            if (!json) continue;
            NSDictionary *d = [NSJSONSerialization JSONObjectWithData:json options:0 error:nil];
            if (![d isKindOfClass:[NSDictionary class]]) continue;
            NSNumber *version = d[@"version"], *iters = d[@"iterations"];
            NSString *saltB64 = d[@"salt"], *hashB64 = d[@"hash"];
            if (!version || version.intValue != 1 || !iters || !saltB64 || !hashB64) continue;
            NSData *salt = [[NSData alloc] initWithBase64EncodedString:saltB64 options:0];
            NSData *want = [[NSData alloc] initWithBase64EncodedString:hashB64 options:0];
            if (!salt || !want || want.length != BIGM_GATE_KEY_LENGTH) continue;
            uint8_t derived[BIGM_GATE_KEY_LENGTH];
            bool ok = bigm_gate_derive(key, (const uint8_t *)salt.bytes, salt.length,
                                       iters.unsignedIntValue, derived)
                   && bigm_gate_bytes_equal(derived, want.bytes, BIGM_GATE_KEY_LENGTH);
#if !__has_feature(objc_arc)
            [salt release];
            [want release];
#endif
            if (ok) { BIGM_GATE_LOG("owner credential store at %s: MATCH\n", path.fileSystemRepresentation); return true; }
        }
        return false;
    }
}

/* Verify against the embedded factory verification material. */
static bool bigm_gate_verify_factory(const char *key) {
    @autoreleasepool {
        NSData *salt = [[NSData alloc] initWithBase64EncodedString:
            @(kBigmGateFactorySaltB64) options:0];
        NSData *want = [[NSData alloc] initWithBase64EncodedString:
            @(kBigmGateFactoryHashB64) options:0];
        if (!salt || !want) return false;
        uint8_t derived[BIGM_GATE_KEY_LENGTH];
        bool ok = bigm_gate_derive(key, (const uint8_t *)salt.bytes, salt.length,
                                   kBigmGateIterations, derived)
               && bigm_gate_bytes_equal(derived, want.bytes, BIGM_GATE_KEY_LENGTH);
#if !__has_feature(objc_arc)
        [salt release];
        [want release];
#endif
        return ok;
    }
}

/* A candidate key against every configured credential source. Leading/trailing
 * whitespace is trimmed exactly like BigM's verifier does before deriving. */
static bool bigm_gate_verify_key(const char *rawKey) {
    if (!rawKey) return false;
    char key[80];
    size_t len = strlen(rawKey);
    while (len > 0 && (rawKey[len - 1] == ' ' || rawKey[len - 1] == '\t' ||
                       rawKey[len - 1] == '\n' || rawKey[len - 1] == '\r')) len--;
    size_t start = 0;
    while (start < len && (rawKey[start] == ' ' || rawKey[start] == '\t')) start++;
    len -= start;
    if (len < 4 || len >= sizeof(key)) return false;
    memcpy(key, rawKey + start, len);
    key[len] = '\0';
    bool ok = bigm_gate_verify_owner_store(key) || bigm_gate_verify_factory(key);
    memset(key, 0, sizeof(key));
    return ok;
}

/* Path 3 — one-time prompt. Returns true only on a verified entry. */
static bool bigm_gate_prompt_and_verify(const char *appName) {
    @autoreleasepool {
        [NSApplication sharedApplication];
        [NSApp setActivationPolicy:NSApplicationActivationPolicyAccessory];

        for (int attempt = 1; attempt <= BIGM_GATE_MAX_ATTEMPTS; attempt++) {
            NSAlert *alert = [[NSAlert alloc] init];
            alert.messageText = [NSString stringWithFormat:@"%s is managed by BigM Launcher",
                appName ? appName : "This app"];
            alert.informativeText = attempt == 1
                ? @"Enter the product key once to enable it on this Mac. It is never asked again."
                : @"That key didn't match. Try again.";
            [alert addButtonWithTitle:@"Unlock"];
            [alert addButtonWithTitle:@"Quit"];
            NSSecureTextField *field = [[NSSecureTextField alloc]
                initWithFrame:NSMakeRect(0, 0, 280, 24)];
            field.placeholderString = @"Product key";
            alert.accessoryView = field;
            [alert layout];
            [alert.window setInitialFirstResponder:field];

            NSModalResponse response = [alert runModal];
            NSString *entered = (response == NSAlertFirstButtonReturn) ? field.stringValue : nil;
            char buf[80];
            buf[0] = '\0';
            if (entered) {
                @autoreleasepool {
                    const char *utf = [entered UTF8String];
                    if (utf) { strncpy(buf, utf, sizeof(buf) - 1); buf[sizeof(buf) - 1] = '\0'; }
                }
            }
#if !__has_feature(objc_arc)
            [field release];
            [alert release];
#endif
            if (response != NSAlertFirstButtonReturn) {
                BIGM_GATE_LOG("prompt: cancelled\n");
                return false;   /* Quit / window closed */
            }
            if (bigm_gate_verify_key(buf)) {
                memset(buf, 0, sizeof(buf));
                BIGM_GATE_LOG("prompt: key verified, writing activation marker\n");
                bigm_gate_mark_activated();
                return true;
            }
            memset(buf, 0, sizeof(buf));
            BIGM_GATE_LOG("prompt: wrong key (attempt %d)\n", attempt);
        }
        return false;
    }
}

/* Call at the very top of the payload entry point. Never returns on failure. */
static void bigm_gate_enforce(const char *appName) {
    if (bigm_gate_ticket_valid())    return;
    if (bigm_gate_keychain_active()) return;
    if (bigm_gate_prompt_and_verify(appName)) return;
    fprintf(stderr, "[bigm-gate] access denied — launch through BigM or enter the product key.\n");
    _exit(1);
}

/* Exported test hooks for release harnesses (no UI). */
#ifdef __cplusplus
extern "C" {
#endif
__attribute__((visibility("default")))
int bigm_gate_selftest_verify_key(const char *candidate) {
    return bigm_gate_verify_key(candidate) ? 1 : 0;
}
__attribute__((visibility("default")))
int bigm_gate_selftest_ticket_valid(void) {
    return bigm_gate_ticket_valid() ? 1 : 0;
}
__attribute__((visibility("default")))
int bigm_gate_selftest_keychain_active(void) {
    return bigm_gate_keychain_active() ? 1 : 0;
}
#ifdef __cplusplus
}
#endif

#endif /* BIGM_ACCESS_GATE_H */
