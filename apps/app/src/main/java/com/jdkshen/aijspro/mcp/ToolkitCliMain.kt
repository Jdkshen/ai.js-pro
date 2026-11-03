package com.jdkshen.aijspro.mcp

/** Child ART entry: never load optional desktop bytecode in the app/tools VM. */
object ToolkitCliMain {
    @JvmStatic fun main(args:Array<String>) {
        try {
            require(args.isNotEmpty()&&args[0]=="brut.apktool.Main")
            System.setProperty("sun.arch.data.model","64")
            System.setProperty("os.name","Linux")
            Class.forName(args[0]).getMethod("main",Array<String>::class.java).invoke(null,args.drop(1).toTypedArray())
        } catch(e:Throwable) {
            val cause=(e as? java.lang.reflect.InvocationTargetException)?.targetException?:e
            System.err.println("APKTOOL_COMPONENT_ERROR: ${cause.javaClass.simpleName}: ${cause.message}")
            cause.printStackTrace();System.exit(1)
        }
    }
}
