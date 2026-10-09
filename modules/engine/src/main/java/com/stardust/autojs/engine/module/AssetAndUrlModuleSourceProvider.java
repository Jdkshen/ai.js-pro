package com.stardust.autojs.engine.module;

import android.content.res.AssetManager;

import com.stardust.autojs.engine.encryption.ScriptEncryption;
import com.stardust.autojs.script.EncryptedScriptFileHeader;

import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.commonjs.module.provider.ModuleSource;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.util.List;

/**
 * Created by Stardust on 2017/5/9.
 */

public class AssetAndUrlModuleSourceProvider extends UrlModuleSourceProvider {

    private android.content.Context mContext;
    private final URI mBaseURI;
    private final String mAssetDirPath;
    private final AssetManager mAssetManager;

    public AssetAndUrlModuleSourceProvider(android.content.Context context, String assetDirPath, List<URI> list) {
        super(list, null);
        mContext = context;
        mAssetDirPath = assetDirPath;
        mBaseURI = URI.create("file:///android_asset/" + assetDirPath);
        mAssetManager = mContext.getAssets();
    }

    @Override
    public ModuleSource loadSource(String moduleId, Scriptable paths, Object validator)
            throws IOException, URISyntaxException {
        // PathAwareRequire 负责在相对路径解析前转义；直接调用 provider 的入口也保持一致。
        return super.loadSource(PathAwareRequire.encodeModuleId(moduleId), paths, validator);
    }

    @Override
    protected ModuleSource loadFromPrivilegedLocations(String moduleId, Object validator) throws IOException, URISyntaxException {
        String moduleIdWithExtension = moduleId;
        if (!moduleIdWithExtension.endsWith(".js")) {
            moduleIdWithExtension += ".js";
        }
        try {
            String assetPath = mAssetDirPath + "/" + moduleIdWithExtension.replace("%20", " ");
            return new ModuleSource(new InputStreamReader(mAssetManager.open(assetPath)), null,
                    new URI(mBaseURI.toString() + "/" + moduleIdWithExtension), mBaseURI, validator);
        } catch (FileNotFoundException e) {
            return super.loadFromPrivilegedLocations(moduleId, validator);
        }
    }

    @Override
    protected Reader getReader(URLConnection urlConnection) throws IOException {
        InputStream stream = urlConnection.getInputStream();
        byte[] bytes = new byte[stream.available()];
        stream.read(bytes);
        stream.close();
        if (EncryptedScriptFileHeader.INSTANCE.isValidFile(bytes)) {
            byte[] clearText = ScriptEncryption.INSTANCE.decrypt(bytes, EncryptedScriptFileHeader.BLOCK_SIZE, bytes.length);
            return new InputStreamReader(new ByteArrayInputStream(clearText));
        }
        return new InputStreamReader(new ByteArrayInputStream(bytes));
    }
}
