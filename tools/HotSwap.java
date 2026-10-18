import com.sun.jdi.Bootstrap;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarFile;

/**
 * Pushes the mod's changed classes into a running Minecraft (HotSwap over the debug port), so a
 * code change shows up without restarting the game. Needs the game started with
 * -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005
 *
 * Run: java --add-modules jdk.jdi tools/HotSwap.java build/libs/wynnlabs-0.1.0.jar [installed.jar] [port]
 *
 * A plain JVM only accepts changed method bodies. New methods, fields, records or classes still
 * need a restart (the installer copies the jar when the game closes); those are reported.
 */
public class HotSwap {

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]);
        Path installed = args.length > 1 ? Path.of(args[1]) : null;
        int port = args.length > 2 ? Integer.parseInt(args[2]) : 5005;
        Path stateFile = jar.resolveSibling("hotswap-state.properties");

        Map<String, byte[]> classes = read(jar);
        // what the game runs now: the last push, or else the installed jar
        Properties state = new Properties();
        if (Files.exists(stateFile)) {
            try (var in = Files.newInputStream(stateFile)) {
                state.load(in);
            }
        } else if (installed != null && Files.exists(installed)) {
            read(installed).forEach((n, b) -> state.setProperty(n, hash(b)));
        }

        VirtualMachine vm = attach(port);
        Map<ReferenceType, byte[]> redefine = new HashMap<>();
        int unchanged = 0;
        int notLoaded = 0;
        Map<String, byte[]> waiting = new HashMap<>();
        StringBuilder fresh = new StringBuilder();
        for (var e : classes.entrySet()) {
            String h = hash(e.getValue());
            if (h.equals(state.getProperty(e.getKey()))) {
                unchanged++;
                continue;
            }
            List<ReferenceType> types = vm.classesByName(e.getKey());
            if (types.isEmpty()) {
                if (state.getProperty(e.getKey()) == null) {
                    fresh.append("  yeni sınıf: ").append(e.getKey()).append('\n');
                } else {
                    notLoaded++;
                    waiting.put(e.getKey(), e.getValue());
                }
                continue;
            }
            for (ReferenceType t : types) {
                redefine.put(t, e.getValue());
            }
        }
        if (redefine.isEmpty()) {
            System.out.println("Değişen yüklü sınıf yok (" + unchanged + " aynı, " + notLoaded + " henüz yüklenmemiş).");
        } else {
            try {
                vm.redefineClasses(redefine);
                System.out.println("Oyuna yüklendi: " + redefine.size() + " sınıf");
                redefine.keySet().forEach(t -> System.out.println("  " + t.name()));
                redefine.forEach((t, b) -> state.setProperty(t.name(), hash(b)));
                try (var out = Files.newOutputStream(stateFile)) {
                    state.store(out, "classes pushed into the running game");
                }
            } catch (UnsupportedOperationException ex) {
                System.out.println("YENİDEN BAŞLATMA GEREKİR: değişiklik yalnız metot içi değil ("
                        + ex.getMessage() + "). Jar oyun kapanınca kurulur.");
                System.exit(2);
            }
        }
        if (!fresh.isEmpty()) {
            System.out.println("Yeni sınıflar oyun açıkken eklenemez, yeniden başlatınca gelir:\n" + fresh);
        }
        if (!waiting.isEmpty()) {
            // not loaded yet: catch the moment the game loads each one and swap it in right then
            System.out.println(waiting.size() + " değişen sınıf henüz yüklenmemiş; oyun yükleyince değiştirilecek (bekleniyor)...");
            var erm = vm.eventRequestManager();
            for (String name : waiting.keySet()) {
                var req = erm.createClassPrepareRequest();
                req.addClassFilter(name);
                req.setSuspendPolicy(com.sun.jdi.request.EventRequest.SUSPEND_EVENT_THREAD);
                req.enable();
            }
            long until = System.currentTimeMillis() + 30 * 60_000L;
            while (!waiting.isEmpty() && System.currentTimeMillis() < until) {
                var set = vm.eventQueue().remove(5_000);
                if (set == null) {
                    continue;
                }
                for (var ev : set) {
                    if (ev instanceof com.sun.jdi.event.ClassPrepareEvent cp && waiting.containsKey(cp.referenceType().name())) {
                        byte[] b = waiting.remove(cp.referenceType().name());
                        try {
                            vm.redefineClasses(Map.of(cp.referenceType(), b));
                            state.setProperty(cp.referenceType().name(), hash(b));
                            System.out.println("Yüklenirken değiştirildi: " + cp.referenceType().name());
                        } catch (UnsupportedOperationException ex) {
                            System.out.println("YENİDEN BAŞLATMA GEREKİR: " + cp.referenceType().name());
                        }
                    }
                }
                set.resume();
            }
            try (var out = Files.newOutputStream(stateFile)) {
                state.store(out, "classes pushed into the running game");
            }
            if (!waiting.isEmpty()) {
                System.out.println("Süre doldu, yüklenmeyen: " + waiting.keySet());
            }
        }
        vm.dispose();
    }

    private static VirtualMachine attach(int port) throws Exception {
        AttachingConnector socket = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(c -> c.transport().name().equals("dt_socket")).findFirst().orElseThrow();
        Map<String, Connector.Argument> a = socket.defaultArguments();
        a.get("hostname").setValue("127.0.0.1");
        a.get("port").setValue(String.valueOf(port));
        try {
            return socket.attach(a);
        } catch (java.io.IOException e) {
            System.out.println("Oyuna bağlanılamadı (127.0.0.1:" + port + "). Oyun hata ayıklama portuyla açık mı?");
            System.exit(3);
            return null;
        }
    }

    /** The mod's own classes in a jar, by binary name. */
    private static Map<String, byte[]> read(Path jar) throws Exception {
        Map<String, byte[]> out = new HashMap<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            var en = jf.entries();
            while (en.hasMoreElements()) {
                var e = en.nextElement();
                String n = e.getName();
                if (!n.endsWith(".class") || !n.startsWith("io/github/anilken/wynnlabs/") || n.contains("/mixin/")) {
                    continue;                 // mixins are applied into Minecraft's classes, not swapped
                }
                try (InputStream in = jf.getInputStream(e)) {
                    out.put(n.substring(0, n.length() - 6).replace('/', '.'), in.readAllBytes());
                }
            }
        }
        return out;
    }

    private static String hash(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
