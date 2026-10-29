import java.io.File;
import java.util.*;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.value.*;

/** Reads the final DEX, without loading or executing APK code. */
class CheckRetrofitSignatures {
    private static String signature(Set<? extends Annotation> annotations) {
        for (Annotation annotation : annotations) {
            if (!annotation.getType().equals("Ldalvik/annotation/Signature;")) continue;
            for (AnnotationElement element : annotation.getElements()) {
                if (!element.getName().equals("value")) continue;
                StringBuilder text = new StringBuilder();
                for (EncodedValue part : ((ArrayEncodedValue) element.getValue()).getValue())
                    text.append(((StringEncodedValue) part).getValue());
                return text.toString();
            }
        }
        return "";
    }

    public static void main(String[] args) throws Exception {
        var container = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        Set<String> wrappers = new HashSet<>(Arrays.asList(
                "Lio/reactivex/Observable;", "Lkotlinx/coroutines/Deferred;"));
        Set<String> foundWrappers = new HashSet<>();
        List<String> failures = new ArrayList<>();
        int checked = 0;
        boolean downloadFound = false;
        for (String entry : container.getDexEntryNames()) {
            for (ClassDef type : container.getEntry(entry).getDexFile().getClasses()) {
                if (wrappers.contains(type.getType())) {
                    foundWrappers.add(type.getType());
                    if (!signature(type.getAnnotations()).startsWith("<"))
                        failures.add(type.getType() + ": missing generic type parameters");
                }
                if (!type.getType().startsWith("Lcom/jdkshen/aijspro/network/api/")) continue;
                for (Method method : type.getMethods()) {
                    String returnType = method.getReturnType();
                    if (!wrappers.contains(returnType)) continue;
                    checked++;
                    String generic = signature(method.getAnnotations());
                    String parameterized = returnType.substring(0, returnType.length() - 1) + "<";
                    if (!generic.contains(parameterized))
                        failures.add(type.getType() + "->" + method.getName() + ": raw " + returnType);
                    if (type.getType().equals("Lcom/jdkshen/aijspro/network/api/DownloadApi;")
                            && method.getName().equals("download")) {
                        downloadFound = true;
                        if (!generic.contains("Lio/reactivex/Observable<Lokhttp3/ResponseBody;>;"))
                            failures.add("DownloadApi.download must return Observable<ResponseBody>");
                        Set<String> annotations = new HashSet<>();
                        for (Annotation a : method.getAnnotations()) annotations.add(a.getType());
                        if (!annotations.contains("Lretrofit2/http/GET;")
                                || !annotations.contains("Lretrofit2/http/Streaming;"))
                            failures.add("DownloadApi.download lost GET/Streaming annotations");
                        if (method.getParameters().size() != 1
                                || method.getParameters().get(0).getAnnotations().stream()
                                .noneMatch(a -> a.getType().equals("Lretrofit2/http/Url;")))
                            failures.add("DownloadApi.download lost its Url parameter annotation");
                    }
                }
            }
        }
        if (!downloadFound) failures.add("DownloadApi.download not found");
        if (!foundWrappers.containsAll(wrappers)) failures.add("Missing API wrapper classes");
        for (String failure : failures) System.err.println("FAIL " + failure);
        if (!failures.isEmpty()) System.exit(1);
        System.out.println("Retrofit DEX signatures: PASS (" + checked + " API methods, "
                + foundWrappers.size() + " wrappers; streaming download annotations retained)");
    }
}
