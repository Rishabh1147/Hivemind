package com.hivemind.platform.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Discovers every {@link Tool}-annotated bean at startup and catalogs it by name — the same
 * self-registration style as {@code @AgentRole}, just collected into a lookup map instead of left
 * implicit, since a planner will eventually need to pick a tool by name rather than by injection.
 *
 * <p>{@link AnnotationUtils#findAnnotation} is used instead of {@code bean.getClass().getAnnotation}
 * because a Spring-proxied bean's runtime class can hide class-level annotations from a plain
 * reflective lookup.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Object> toolsByName = new HashMap<>();

    public ToolRegistry(ApplicationContext applicationContext) {
        applicationContext.getBeansWithAnnotation(Tool.class).forEach((beanName, bean) -> {
            Tool tool = AnnotationUtils.findAnnotation(bean.getClass(), Tool.class);
            if (tool == null) {
                return;
            }
            toolsByName.put(tool.name(), bean);
            log.info("Registered tool '{}' (vertical={}) -> {}", tool.name(), tool.vertical(), bean.getClass().getSimpleName());
        });
    }

    public Optional<Object> get(String name) {
        return Optional.ofNullable(toolsByName.get(name));
    }

    public Set<String> names() {
        return Set.copyOf(toolsByName.keySet());
    }
}
