import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.MultiDexContainer;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.Reference;
import com.android.tools.smali.dexlib2.iface.reference.TypeReference;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

/**
 * java -Xmx3500m -cp desktop.jar Propagate.java <old.apk> <new.apk> <seeds.tsv> <owners.txt> <out.tsv>
 * (scripts/retarget/retarget.ps1 runs it; the desktop jar carries dexlib2.)
 * Follows R8 renames outward from known class pairs. Same-named classes and seeds.tsv ("old new") start it;
 * each pair's methods are aligned by shape, and the references of aligned bodies vote on what every other
 * obfuscated class, method and field became. Members are only recorded for owners listed in owners.txt.
 * Output: C old new votes total / M owner name desc newOwner newName newDesc votes total / F likewise /
 * R oldId newId votes total: resource id literals (0x7f...) paired in order inside aligned method bodies.
 * A seed with no votes of its own is written with status "seed"; rename.py takes those like "accepted".
 */
public class Propagate {
    static final Pattern OBF_TYPE = Pattern.compile("LX/[^;]+;|LY/[^;]+;|Lkotlin/jvm/internal/A[A-Za-z]*S\\d[^;]*;");
    static final Pattern OBF_NAME = Pattern.compile("^[LIJZF]+$|^.{1,2}$");

    static Map<String, ClassDef> load(String apk) throws Exception {
        Map<String, ClassDef> out = new HashMap<>(1 << 20);
        MultiDexContainer<? extends DexFile> c = DexFileFactory.loadDexContainer(new File(apk), Opcodes.getDefault());
        for (String entry : c.getDexEntryNames()) {
            for (ClassDef d : c.getEntry(entry).getDexFile().getClasses()) out.putIfAbsent(d.getType(), d);
        }
        return out;
    }

    static boolean obf(String type) { return OBF_TYPE.matcher(type).find(); }
    static String norm(String type) { return OBF_TYPE.matcher(type).replaceAll("LX;"); }
    static String base(String type) { return type.replaceAll("^\\[+", ""); }
    static boolean obfName(String name) { return OBF_NAME.matcher(name.replaceAll("\\$\\d+$", "")).matches(); }
    static String bare(String name) { return name.replaceAll("\\$\\d+$", ""); }

    static String desc(Method m) {
        StringBuilder s = new StringBuilder("(");
        for (CharSequence p : m.getParameterTypes()) s.append(p);
        return s.append(')').append(m.getReturnType()).toString();
    }
    static String desc(MethodReference m) {
        StringBuilder s = new StringBuilder("(");
        for (CharSequence p : m.getParameterTypes()) s.append(p);
        return s.append(')').append(m.getReturnType()).toString();
    }
    static String key(Method m) {
        StringBuilder s = new StringBuilder(obfName(m.getName()) ? "?" : bare(m.getName())).append('(');
        for (CharSequence p : m.getParameterTypes()) s.append(norm(p.toString()));
        return s.append(')').append(norm(m.getReturnType())).append(':').append(m.getAccessFlags()).toString();
    }

    static final Map<String, Map<String, Integer>> typeVotes = new HashMap<>();
    static final Map<String, Map<String, Integer>> memberVotes = new HashMap<>();
    static Set<String> owners;
    static final Map<String, String> mapped = new HashMap<>();

    static void voteType(String a, String b) {
        a = base(a); b = base(b);
        if (!obf(a) && !obf(b)) return;
        if (!a.startsWith("L") || !b.startsWith("L")) return;
        typeVotes.computeIfAbsent(a, k -> new HashMap<>()).merge(b, 1, Integer::sum);
    }
    static void voteMember(String old, String now) {
        memberVotes.computeIfAbsent(old, k -> new HashMap<>()).merge(now, 1, Integer::sum);
    }

    static void voteRefs(Reference a, Reference b) {
        if (a instanceof TypeReference && b instanceof TypeReference) {
            voteType(((TypeReference) a).getType(), ((TypeReference) b).getType());
        } else if (a instanceof FieldReference && b instanceof FieldReference) {
            FieldReference x = (FieldReference) a, y = (FieldReference) b;
            voteType(x.getDefiningClass(), y.getDefiningClass());
            voteType(x.getType(), y.getType());
            if (owners.contains(x.getDefiningClass())) {
                voteMember("F\t" + x.getDefiningClass() + "\t" + x.getName() + "\t" + x.getType(),
                        y.getDefiningClass() + "\t" + y.getName() + "\t" + y.getType());
            }
        } else if (a instanceof MethodReference && b instanceof MethodReference) {
            MethodReference x = (MethodReference) a, y = (MethodReference) b;
            voteType(x.getDefiningClass(), y.getDefiningClass());
            voteType(x.getReturnType(), y.getReturnType());
            List<? extends CharSequence> p = x.getParameterTypes(), q = y.getParameterTypes();
            if (p.size() == q.size()) for (int i = 0; i < p.size(); i++) voteType(p.get(i).toString(), q.get(i).toString());
            if (owners.contains(x.getDefiningClass())) {
                voteMember("M\t" + x.getDefiningClass() + "\t" + x.getName() + "\t" + desc(x),
                        y.getDefiningClass() + "\t" + y.getName() + "\t" + desc(y));
            }
        }
    }

    static void walk(Method a, Method b) {
        voteType(a.getReturnType(), b.getReturnType());
        List<? extends CharSequence> p = a.getParameterTypes(), q = b.getParameterTypes();
        if (p.size() == q.size()) for (int i = 0; i < p.size(); i++) voteType(p.get(i).toString(), q.get(i).toString());
        if (owners.contains(a.getDefiningClass())) {
            voteMember("M\t" + a.getDefiningClass() + "\t" + a.getName() + "\t" + desc(a),
                    b.getDefiningClass() + "\t" + b.getName() + "\t" + desc(b));
        }
        MethodImplementation x = a.getImplementation(), y = b.getImplementation();
        if (x == null || y == null) return;
        List<Reference> left = refs(x), right = refs(y);
        if (left.size() != right.size()) return;
        for (int i = 0; i < left.size(); i++) {
            if (left.get(i).getClass() != right.get(i).getClass()) return;
        }
        for (int i = 0; i < left.size(); i++) voteRefs(left.get(i), right.get(i));
        List<Integer> li = ids(x), ri = ids(y);
        if (li.size() == ri.size()) {
            for (int i = 0; i < li.size(); i++) idVotes.computeIfAbsent(li.get(i), k -> new HashMap<>()).merge(ri.get(i), 1, Integer::sum);
        }
    }

    static final Map<Integer, Map<Integer, Integer>> idVotes = new HashMap<>();

    static List<Integer> ids(MethodImplementation impl) {
        List<Integer> out = new ArrayList<>();
        for (Instruction ins : impl.getInstructions()) {
            if (ins instanceof NarrowLiteralInstruction) {
                int v = ((NarrowLiteralInstruction) ins).getNarrowLiteral();
                if ((v >>> 24) == 0x7f && ((v >>> 16) & 0xff) != 0) out.add(v);
            }
        }
        return out;
    }

    static List<Reference> refs(MethodImplementation impl) {
        List<Reference> out = new ArrayList<>();
        for (Instruction ins : impl.getInstructions()) {
            if (ins instanceof ReferenceInstruction) {
                Opcode op = ins.getOpcode();
                if (op == Opcode.CONST_STRING || op == Opcode.CONST_STRING_JUMBO) continue;
                out.add(((ReferenceInstruction) ins).getReference());
            }
        }
        return out;
    }

    static long shape(Method m) {
        MethodImplementation impl = m.getImplementation();
        if (impl == null) return -1;
        long h = 1125899906842597L;
        for (Instruction ins : impl.getInstructions()) h = 31 * h + ins.getOpcode().ordinal();
        return h;
    }

    static void pair(ClassDef a, ClassDef b) {
        if (a.getSuperclass() != null && b.getSuperclass() != null) voteType(a.getSuperclass(), b.getSuperclass());
        List<String> ai = new ArrayList<>(a.getInterfaces()), bi = new ArrayList<>(b.getInterfaces());
        if (ai.size() == bi.size()) for (int i = 0; i < ai.size(); i++) voteType(ai.get(i), bi.get(i));

        Map<String, List<Field>> af = new HashMap<>(), bf = new HashMap<>();
        for (Field f : a.getFields()) af.computeIfAbsent(norm(f.getType()) + ":" + f.getAccessFlags(), k -> new ArrayList<>()).add(f);
        for (Field f : b.getFields()) bf.computeIfAbsent(norm(f.getType()) + ":" + f.getAccessFlags(), k -> new ArrayList<>()).add(f);
        for (Map.Entry<String, List<Field>> e : af.entrySet()) {
            List<Field> other = bf.get(e.getKey());
            if (other == null || other.size() != e.getValue().size()) continue;
            if (other.size() == 1) {
                Field x = e.getValue().get(0), y = other.get(0);
                voteType(x.getType(), y.getType());
                if (owners.contains(a.getType())) {
                    voteMember("F\t" + a.getType() + "\t" + x.getName() + "\t" + x.getType(), b.getType() + "\t" + y.getName() + "\t" + y.getType());
                }
            }
        }

        Map<String, List<Method>> am = new HashMap<>(), bm = new HashMap<>();
        for (Method m : a.getMethods()) am.computeIfAbsent(key(m), k -> new ArrayList<>()).add(m);
        for (Method m : b.getMethods()) bm.computeIfAbsent(key(m), k -> new ArrayList<>()).add(m);
        for (Map.Entry<String, List<Method>> e : am.entrySet()) {
            List<Method> xs = e.getValue(), ys = bm.get(e.getKey());
            if (ys == null) continue;
            if (xs.size() == 1 && ys.size() == 1) {
                walk(xs.get(0), ys.get(0));
                continue;
            }
            Map<Long, List<Method>> xh = new HashMap<>(), yh = new HashMap<>();
            for (Method m : xs) xh.computeIfAbsent(shape(m), k -> new ArrayList<>()).add(m);
            for (Method m : ys) yh.computeIfAbsent(shape(m), k -> new ArrayList<>()).add(m);
            for (Map.Entry<Long, List<Method>> h : xh.entrySet()) {
                List<Method> other = yh.get(h.getKey());
                if (other != null && other.size() == 1 && h.getValue().size() == 1) walk(h.getValue().get(0), other.get(0));
            }
        }
    }

    static String top(Map<String, Integer> votes) {
        String best = null;
        int bestCount = -1;
        for (Map.Entry<String, Integer> e : votes.entrySet()) {
            if (e.getValue() > bestCount) { best = e.getKey(); bestCount = e.getValue(); }
        }
        return best;
    }

    public static void main(String[] args) throws Exception {
        Map<String, ClassDef> oldClasses = load(args[0]), newClasses = load(args[1]);
        System.out.println("loaded " + oldClasses.size() + " / " + newClasses.size());
        owners = new HashSet<>(Files.readAllLines(Paths.get(args[3])));
        Deque<String> queue = new ArrayDeque<>();
        for (String type : oldClasses.keySet()) {
            if (!obf(type) && newClasses.containsKey(type)) queue.add(type + "\t" + type);
        }
        Set<String> takenNew = new HashSet<>();
        for (String line : Files.readAllLines(Paths.get(args[2]))) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2 && oldClasses.containsKey(parts[0]) && newClasses.containsKey(parts[1])) {
                mapped.put(parts[0], parts[1]);
                takenNew.add(parts[1]);
                queue.add(parts[0] + "\t" + parts[1]);
            }
        }
        System.out.println("seeds " + queue.size());
        Set<String> done = new HashSet<>();
        for (int round = 1; round <= 12 && !queue.isEmpty(); round++) {
            int walked = 0;
            while (!queue.isEmpty()) {
                String[] p = queue.poll().split("\t");
                if (!done.add(p[0])) continue;
                pair(oldClasses.get(p[0]), newClasses.get(p[1]));
                walked++;
            }
            int added = 0;
            for (Map.Entry<String, Map<String, Integer>> e : typeVotes.entrySet()) {
                String old = e.getKey();
                if (mapped.containsKey(old) || !obf(old) || !oldClasses.containsKey(old)) continue;
                String best = top(e.getValue());
                int count = e.getValue().get(best), total = e.getValue().values().stream().mapToInt(Integer::intValue).sum();
                if (best == null || !newClasses.containsKey(best) || takenNew.contains(best)) continue;
                if (count * 10 >= total * 8 && (count >= 2 || total == 1)) {
                    mapped.put(old, best);
                    takenNew.add(best);
                    queue.add(old + "\t" + best);
                    added++;
                }
            }
            System.out.println("round " + round + ": walked " + walked + ", new pairs " + added + ", mapped " + mapped.size());
        }
        try (PrintStream out = new PrintStream(args[4], "UTF-8")) {
            for (Map.Entry<String, Map<String, Integer>> e : typeVotes.entrySet()) {
                if (!oldClasses.containsKey(e.getKey()) || !obf(e.getKey())) continue;
                String best = mapped.getOrDefault(e.getKey(), top(e.getValue()));
                int count = e.getValue().getOrDefault(best, 0), total = e.getValue().values().stream().mapToInt(Integer::intValue).sum();
                out.println("C\t" + e.getKey() + "\t" + best + "\t" + count + "\t" + total + "\t" + (mapped.containsKey(e.getKey()) ? "accepted" : "open"));
            }
            for (Map.Entry<String, String> e : mapped.entrySet()) {
                if (!typeVotes.containsKey(e.getKey())) out.println("C\t" + e.getKey() + "\t" + e.getValue() + "\t0\t0\tseed");
            }
            for (Map.Entry<String, Map<String, Integer>> e : memberVotes.entrySet()) {
                String best = top(e.getValue());
                int total = e.getValue().values().stream().mapToInt(Integer::intValue).sum();
                out.println(e.getKey() + "\t" + best + "\t" + e.getValue().get(best) + "\t" + total);
            }
            for (Map.Entry<Integer, Map<Integer, Integer>> e : idVotes.entrySet()) {
                int best = -1, bestCount = -1, total = 0;
                for (Map.Entry<Integer, Integer> v : e.getValue().entrySet()) {
                    total += v.getValue();
                    if (v.getValue() > bestCount) { best = v.getKey(); bestCount = v.getValue(); }
                }
                out.println(String.format("R\t0x%08x\t0x%08x\t%d\t%d", e.getKey(), best, bestCount, total));
            }
        }
        System.out.println("done");
    }
}
