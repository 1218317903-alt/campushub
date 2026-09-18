package ai.camphub.workspace.config;

import ai.camphub.workspace.app.AuthorizationService;
import ai.camphub.workspace.app.ObjectStorage;
import ai.camphub.workspace.infrastructure.scope.WorkspaceScopeInterceptor;
import ai.camphub.workspace.infrastructure.storage.LocalFileObjectStorage;
import java.nio.file.Path;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 空间模块的装配点。
 *
 * <h2>为什么这里有三件看起来不相干的东西</h2>
 * 它们有一个共同点：<b>都是"把某段逻辑接到某个扩展点上"的决定</b>，
 * 而不是业务规则。业务规则在 {@code app} 层，适配实现分别在
 * {@code infrastructure.scope} 与 {@code infrastructure.storage}，
 * 装配集中在这里，使得"这个模块接入了哪些机制"一眼可见。
 */
@Configuration
public class WorkspaceConfig {

    /**
     * 把第三层防线注册进 MyBatis。
     *
     * <h2>为什么用 ConfigurationCustomizer 而不是把拦截器标成组件</h2>
     * MyBatis 的拦截器必须被 {@code Configuration.addInterceptor} 显式登记才会生效 ——
     * 一个没有被登记的 {@code Interceptor} Bean 是一个<b>看起来在工作、实际不存在</b>的组件：
     * 它会被注入、会被测试构造出来、方法也都能调，只是永远不会被框架调用。
     * 用 {@code ConfigurationCustomizer} 表达"登记"这个动作，让漏登记不可能发生。
     *
     * <p>{@code ObjectProvider} 是刻意的：拦截器在构造时不能拿到
     * {@link AuthorizationService}，否则会形成
     * "拦截器 → 授权服务 → Mapper → SqlSessionFactory → 拦截器" 的构造环。
     * 它在真正拦截时才通过 provider 取用，那时容器已经装配完毕。
     *
     * @param authorizationService 授权服务的懒提供者
     * @return 配置定制器
     */
    @Bean
    public ConfigurationCustomizer workspaceScopeConfigurationCustomizer(
            ObjectProvider<AuthorizationService> authorizationService) {
        WorkspaceScopeInterceptor interceptor = new WorkspaceScopeInterceptor(authorizationService);
        return configuration -> configuration.addInterceptor(interceptor);
    }

    /**
     * 对象存储适配器。Phase 04 用本地磁盘；Phase 05 增加对象存储实现后，
     * 改动只发生在这个方法里 —— 应用层依赖的是 {@link ObjectStorage} 端口。
     *
     * @param properties 空间模块配置
     * @return 存储实现
     */
    @Bean
    public ObjectStorage objectStorage(WorkspaceProperties properties) {
        return new LocalFileObjectStorage(Path.of(properties.documents().storageDir()));
    }
}
