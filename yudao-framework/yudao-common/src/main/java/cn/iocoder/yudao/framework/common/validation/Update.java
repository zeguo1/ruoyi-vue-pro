package cn.iocoder.yudao.framework.common.validation;

import jakarta.validation.groups.Default;

/** Update-only requirements in shared create/update requests, together with all default constraints. */
public interface Update extends Default {
}
