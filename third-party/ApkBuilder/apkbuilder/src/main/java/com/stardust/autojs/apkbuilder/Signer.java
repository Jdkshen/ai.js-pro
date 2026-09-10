package com.stardust.autojs.apkbuilder;

import java.io.File;

/**
 * Turns the unpacked APK workspace into a signed APK.
 *
 * <p>The legacy behaviour (and the default when no signer is set) is tiny-sign with its
 * built-in test certificate. The app supplies its own implementation when the user picks a
 * private signing key, so every APK a user publishes can carry a unique identity instead of
 * the public certificate shared by every tiny-sign based packager.
 */
public interface Signer {

    void sign(File workspaceDir, File outputApk) throws Exception;
}
