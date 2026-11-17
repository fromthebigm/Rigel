import java.lang.foreign.*;
import java.lang.invoke.*;

public class ObjCBlockTest {
    public static void main(String[] args) throws Throwable {
        Linker linker = Linker.nativeLinker();
        SymbolLookup def = Linker.nativeLinker().defaultLookup();
        for (String s : new String[]{"_NSConcreteStackBlock", "_NSConcreteGlobalBlock", "_Block_copy", "objc_msgSend"}) {
            System.out.println(s + " -> " + def.find(s));
        }
    }
}
