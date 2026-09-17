package ai.camphub.identity.infrastructure.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;

/**
 * 加载常用弱密码表。
 *
 * <p>表放在 classpath 资源 {@code security/common-passwords.txt}，而不是写死在代码里：
 * 它是<b>数据</b>，会随着新泄露事件补充，改它不该需要重新读一遍 Java。
 *
 * <h2>读取失败必须让应用起不来</h2>
 * 如果这个文件因为打包疏漏而缺失，而代码只是打个 warn 然后返回空集合，
 * 结果就是"密码策略看起来生效了，实际上只检查长度"——
 * 一个静默失效的安全控制，比一个明确报错的缺失要危险得多。
 * 因此这里选择直接抛异常，让问题在启动时暴露。
 */
public final class CommonPasswordList {

    /** classpath 路径。 */
    private static final String RESOURCE_PATH = "security/common-passwords.txt";

    private CommonPasswordList() {
    }

    /**
     * 读取弱密码表。
     *
     * @return 全部小写的密码集合；空行与 {@code #} 开头的注释行被忽略
     * @throws IllegalStateException 资源缺失或读取失败时
     */
    public static Set<String> load() {
        Set<String> passwords = new LinkedHashSet<>();
        ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
        try (InputStream in = resource.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                passwords.add(trimmed.toLowerCase(Locale.ROOT));
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "无法读取常用弱密码表 " + RESOURCE_PATH + "，密码策略将不完整，拒绝以这种状态启动", e);
        }
        if (passwords.isEmpty()) {
            throw new IllegalStateException(
                    "常用弱密码表 " + RESOURCE_PATH + " 为空，密码策略将形同虚设，拒绝以这种状态启动");
        }
        return Set.copyOf(passwords);
    }
}
