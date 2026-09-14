package cn.iocoder.yudao.framework.swagger.config;

import org.springdoc.core.providers.SpringDocJavadocProvider;

/** Array containers have no class Javadoc; only their component classes do. */
public class ContractJavadocProvider extends SpringDocJavadocProvider {
    @Override
    public String getClassJavadoc(Class<?> type) {
        return type.isArray() || type.isPrimitive() ? null : super.getClassJavadoc(type);
    }
}
