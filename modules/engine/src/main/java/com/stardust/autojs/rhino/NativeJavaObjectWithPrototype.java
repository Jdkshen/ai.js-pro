package com.stardust.autojs.rhino;

import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Symbol;
import org.mozilla.javascript.SymbolScriptable;

/**
 * Created by Stardust on 2018/4/4.
 */

public class NativeJavaObjectWithPrototype extends NativeJavaObject implements SymbolScriptable {

    private final NativeObject symbolProperties = new NativeObject();
    private final java.util.HashSet<Symbol> writingPrototypeSymbols = new java.util.HashSet<>();


    public NativeJavaObjectWithPrototype(Scriptable scope, Object javaObject, Class<?> staticType) {
        super(scope, javaObject, staticType);
    }

    public NativeJavaObjectWithPrototype(Scriptable scope, Object javaObject, Class<?> staticType, boolean isAdapter) {
        super(scope, javaObject, staticType, isAdapter);
    }

    public NativeJavaObjectWithPrototype() {
    }

    @Override
    public boolean has(String name, Scriptable start) {
        return super.has(name, start) || (prototype != null && prototype.has(name, start))
                || name.equals("__proto__");
    }

    @Override
    public Object get(String name, Scriptable start) {
        if (name.equals("__proto__")) {
            return prototype;
        }
        if(super.has(name, start)){
            return super.get(name, start);
        }
        if (prototype == null) {
            return Scriptable.NOT_FOUND;
        }
        return prototype.get(name, start);
    }

    @Override
    public void put(String name, Scriptable start, Object value) {
        if (name.equals("__proto__")) {
            prototype = (Scriptable) value;
            return;
        }
        super.put(name, start, value);
    }

    @Override
    public Object get(Symbol key, Scriptable start) {
        if (symbolProperties.has(key, symbolProperties)) return symbolProperties.get(key, symbolProperties);
        return prototype instanceof SymbolScriptable
                ? ((SymbolScriptable) prototype).get(key, start) : Scriptable.NOT_FOUND;
    }

    @Override
    public boolean has(Symbol key, Scriptable start) {
        return symbolProperties.has(key, symbolProperties)
                || (prototype instanceof SymbolScriptable && ((SymbolScriptable) prototype).has(key, start));
    }

    @Override
    public void put(Symbol key, Scriptable start, Object value) {
        if (start != this) {
            ((SymbolScriptable) start).put(key, start, value);
        } else if (!symbolProperties.has(key, symbolProperties)
                && !writingPrototypeSymbols.contains(key)
                && prototype instanceof SymbolScriptable
                && ((SymbolScriptable) prototype).has(key, start)) {
            writingPrototypeSymbols.add(key);
            try {
                ((SymbolScriptable) prototype).put(key, start, value);
            } finally {
                writingPrototypeSymbols.remove(key);
            }
        } else {
            symbolProperties.put(key, symbolProperties, value);
        }
    }

    @Override
    public void delete(Symbol key) {
        symbolProperties.delete(key);
    }

}
