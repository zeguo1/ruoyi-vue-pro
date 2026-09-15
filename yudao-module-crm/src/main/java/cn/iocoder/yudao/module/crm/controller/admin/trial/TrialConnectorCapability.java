package cn.iocoder.yudao.module.crm.controller.admin.trial;
import java.lang.annotation.*;
@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME)
public @interface TrialConnectorCapability { String value(); }
