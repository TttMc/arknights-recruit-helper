import com.ark.recruit.Recruit;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * 算法回归测试（纯 JVM，不依赖 Android）。
 *
 * 目的：验证 v2.9.2 修的「新手必出写死 1★」以及各特殊标签的必出星级，
 * 期望值全部来自 PRTS 公开招募页（2026-08-01 池）与 operators.xml 实际数据。
 */
public class AlgoTest {

    static int fail = 0, pass = 0;

    public static void main(String[] a) throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get(a[0])), StandardCharsets.UTF_8);
        List<String> rows = new ArrayList<>();
        Matcher m = Pattern.compile("<item>([^<]+)</item>").matcher(xml);
        while (m.find()) rows.add(m.group(1));
        Recruit.load(rows.toArray(new String[0]));
        System.out.println("加载干员 " + rows.size() + " 条");

        // 单标签：必出星级
        expect("新手", 2);
        expect("支援机械", 1);
        expect("资深干员", 5);
        expect("高级资深干员", 6);
        expect("爆发", 4);
        expect("控场", 4);
        expect("召唤", 4);
        expect("位移", 4);
        expect("削弱", 4);
        expect("快速复活", 4);
        expect("防护", 3);
        expect("治疗", 3);
        expect("减速", 3);
        expect("输出", 3);
        expect("近战位", 3);

        // 组合：资深系锁星，且不被普通池拉低
        expectCombo(new String[]{"资深干员", "治疗"}, 5);
        expectCombo(new String[]{"高级资深干员", "输出"}, 6);
        // 3 标签：召唤 + 辅助干员 + 远程位 → 凯尔希6/梅尔5/豆苗? 豆苗是先锋，不在辅助。
        expectCombo(new String[]{"召唤", "辅助干员", "远程位"}, 5);
        // 支援机械必须单独归类，且不被资深覆盖
        Combo("支援机械");
        // 新手文案
        textChecks();

        System.out.println("\n通过 " + pass + "，失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    static Recruit.Combo Combo(String tag) {
        List<Recruit.Combo> cs = Recruit.analyze(new String[]{tag}, Recruit.MAX_TAGS);
        for (Recruit.Combo c : cs) if (c.tags.length == 1 && tag.equals(c.tags[0])) return c;
        return null;
    }

    static void expect(String tag, int want) {
        Recruit.Combo c = Combo(tag);
        if (c == null) { bad(tag, "组合不存在", "存在"); return; }
        if (c.guaranteed == want && c.ceiling >= want) {
            ok(tag + " 必出=" + c.guaranteed + "★ 最高=" + c.ceiling + "★");
        } else {
            bad(tag + " 必出=" + c.guaranteed + "★ 最高=" + c.ceiling + "★",
                "必出=" + want + "★", "必出=" + c.guaranteed + "★");
        }
    }

    static void expectCombo(String[] tags, int want) {
        List<Recruit.Combo> cs = Recruit.analyze(tags, Recruit.MAX_TAGS);
        Recruit.Combo hit = null;
        for (Recruit.Combo c : cs) {
            if (c.tags.length != tags.length) continue;
            List<String> x = new ArrayList<>(Arrays.asList(c.tags));
            List<String> y = new ArrayList<>(Arrays.asList(tags));
            Collections.sort(x); Collections.sort(y);
            if (x.equals(y)) { hit = c; break; }
        }
        String name = String.join("+", tags);
        if (hit == null) { bad(name, "必出=" + want + "★", "组合不存在"); return; }
        if (hit.guaranteed == want) ok(name + " 必出=" + hit.guaranteed + "★ 时长=" + hit.minTime);
        else bad(name, "必出=" + want + "★", "必出=" + hit.guaranteed + "★");
    }

    static void textChecks() {
        Recruit.Combo nx = Combo("新手");
        if (nx == null) { bad("新手文案", "含「仅2★」", "组合不存在"); return; }
        String t = nx.countText();
        // 新手全是 2★ → 应写「仅2★」，不能再写「仅1~2★」
        if (t.contains("仅2★") && !t.contains("1~2")) ok("新手 countText = " + t);
        else bad("新手 countText = " + t, "含「仅2★」", t);

        Recruit.Combo mj = Combo("支援机械");
        if (mj == null) { bad("支援机械文案", "含「仅1★」", "组合不存在"); return; }
        String t2 = mj.countText();
        if (t2.contains("仅1★") && !t2.contains("1~2")) ok("支援机械 countText = " + t2);
        else bad("支援机械 countText = " + t2, "含「仅1★」", t2);
        if (mj.wastedOnLongRun && mj.supportMech) ok("支援机械标记正确的 wasted+supportMech");
        else bad("支援机械标记", "wasted=true,mech=true", "wasted=" + mj.wastedOnLongRun + ",mech=" + mj.supportMech);
    }

    static void ok(String s) { pass++; System.out.println("  OK   " + s); }
    static void bad(String what, String want, String got) {
        fail++;
        System.out.println("  FAIL " + what + "  期望 " + want + "  实际 " + got);
    }
}
