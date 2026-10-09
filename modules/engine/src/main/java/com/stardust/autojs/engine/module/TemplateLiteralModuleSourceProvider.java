package com.stardust.autojs.engine.module;

import com.stardust.autojs.engine.RhinoSyntaxPreprocessor;

import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.commonjs.module.provider.ModuleSource;
import org.mozilla.javascript.commonjs.module.provider.ModuleSourceProvider;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;

/** Applies the entry script's template compatibility to assets, files and nested require calls. */
public final class TemplateLiteralModuleSourceProvider implements ModuleSourceProvider {
    private final ModuleSourceProvider delegate;

    public TemplateLiteralModuleSourceProvider(ModuleSourceProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public ModuleSource loadSource(String moduleId, Scriptable paths, Object validator)
            throws IOException, URISyntaxException {
        return preprocess(delegate.loadSource(moduleId, paths, validator));
    }

    @Override
    public ModuleSource loadSource(URI uri, URI base, Object validator)
            throws IOException, URISyntaxException {
        return preprocess(delegate.loadSource(uri, base, validator));
    }

    static ModuleSource preprocess(ModuleSource source) throws IOException {
        if (source == null || source == NOT_MODIFIED) {
            return source;
        }
        StringBuilder text = new StringBuilder();
        try (Reader reader = source.getReader()) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                text.append(buffer, 0, count);
            }
        }
        String original = text.toString();
        String converted = RhinoSyntaxPreprocessor.apply(original);
        // Unsupported templates remain a syntax error; modules cannot switch their parent's engine.
        return new ModuleSource(new StringReader(converted == null ? original : converted),
                source.getSecurityDomain(), source.getUri(), source.getBase(), source.getValidator());
    }
}
