package com.quanta.demo0.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.lang.reflect.Modifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** 按域分包门禁：领域通过 Service/DTO/VO 通信，持久化和实现类留在所属域。 */
@AnalyzeClasses(packages = "com.quanta.demo0", importOptions = ImportOption.DoNotIncludeTests.class)
class PackageArchitectureTest {

    @ArchTest
    static final ArchRule noLegacyTopLevelPackages = noClasses().should().resideInAnyPackage(
            "com.quanta.demo0.controller..", "com.quanta.demo0.service..",
            "com.quanta.demo0.mapper..", "com.quanta.demo0.entity..",
            "com.quanta.demo0.dto..", "com.quanta.demo0.vo..",
            "com.quanta.demo0.es..", "com.quanta.demo0.mq..",
            "com.quanta.demo0.config..", "com.quanta.demo0.security..",
            "com.quanta.demo0.modertion..", "com.quanta.demo0.enums..",
            "com.quanta.demo0.policy..", "com.quanta.demo0.properties..",
            "com.quanta.demo0.result..", "com.quanta.demo0.annotation..",
            "com.quanta.demo0.aop..", "com.quanta.demo0.handler..",
            "com.quanta.demo0.utils..", "com.quanta.demo0.constant..",
            "com.quanta.demo0.exception..");

    @ArchTest
    static final ArchRule controllersDoNotAccessMappers = noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAPackage("..mapper..");

    @ArchTest
    static final ArchRule servicesAndMappersDoNotDependOnControllers = noClasses()
            .that().resideInAnyPackage("..service..", "..mapper..", "..mq..")
            .should().dependOnClassesThat().resideInAPackage("..controller..");

    @ArchTest
    static void domainInternalsStayInTheirDomain(JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (String domain : new String[]{"content", "answer", "comment", "user", "identity",
                "interaction", "follow", "feed", "notification", "moderation", "search"}) {
            String prefix = "com.quanta.demo0." + domain;
            var result = noClasses().that().resideOutsideOfPackage(prefix + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            prefix + ".mapper..", prefix + ".entity..", prefix + ".service.impl..")
                    .allowEmptyShould(true).evaluate(classes);
            violations.addAll(result.getFailureReport().getDetails());
        }
        if (!violations.isEmpty()) {
            throw new AssertionError(String.join(System.lineSeparator(), violations));
        }
    }

    /**
     * 检查接口倒置后的实际 Service 注入图，而非禁止领域之间必要的双向业务契约。
     * 私有实现隔离并不能单独发现 A→B接口→A 的 Spring Bean 循环。
     */
    @ArchTest
    static void serviceInjectionHasNoCycles(JavaClasses classes) {
        List<Class<?>> services = new ArrayList<>();
        classes.forEach(javaClass -> {
            if (javaClass.getName().startsWith("com.quanta.demo0.")
                    && javaClass.getPackageName().contains(".service.impl")) {
                Class<?> type = javaClass.reflect();
                if (!type.isInterface() && !Modifier.isAbstract(type.getModifiers())
                        && AnnotatedElementUtils.hasAnnotation(type, Component.class)) {
                    services.add(type);
                }
            }
        });
        Map<Class<?>, List<Class<?>>> graph = new HashMap<>();
        for (Class<?> service : services) {
            List<Class<?>> dependencies = new ArrayList<>();
            for (var field : service.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || !field.getType().getName().startsWith("com.quanta.demo0.")
                        || (!Modifier.isFinal(field.getModifiers())
                        && !field.isAnnotationPresent(Autowired.class))) {
                    continue;
                }
                services.stream().filter(field.getType()::isAssignableFrom)
                        .forEach(dependencies::add);
            }
            graph.put(service, dependencies);
        }
        Set<Class<?>> complete = new HashSet<>();
        for (Class<?> service : services) {
            checkCycle(service, graph, new ArrayList<>(), complete);
        }
    }

    private static void checkCycle(Class<?> service, Map<Class<?>, List<Class<?>>> graph,
                                   List<Class<?>> path, Set<Class<?>> complete) {
        if (path.contains(service)) {
            path.add(service);
            throw new AssertionError("Service injection cycle: "
                    + path.stream().map(Class::getSimpleName).toList());
        }
        if (complete.contains(service)) {
            return;
        }
        path.add(service);
        for (Class<?> dependency : graph.getOrDefault(service, List.of())) {
            checkCycle(dependency, graph, path, complete);
        }
        path.remove(path.size() - 1);
        complete.add(service);
    }
}
