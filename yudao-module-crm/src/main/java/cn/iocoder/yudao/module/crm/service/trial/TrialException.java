package cn.iocoder.yudao.module.crm.service.trial;

import cn.iocoder.yudao.framework.common.exception.ServiceException;

/** Stable, non-sensitive messages only. Never include external response bodies. */
public final class TrialException {
    private TrialException() { }
    public static ServiceException error(int suffix, String message) {
        return new ServiceException(1_020_100_000 + suffix, message);
    }
    public static ServiceException unauthorized() { return error(1, "试用服务身份或签名无效"); }
    public static ServiceException replay() { return error(2, "请求已使用，请使用新 nonce 查询申请状态"); }
    public static ServiceException unavailable() { return error(3, "试用开户配置尚未就绪"); }
    public static ServiceException notFound() { return error(4, "申请不存在或不属于当前身份"); }
    public static ServiceException conflict() { return error(5, "幂等键与原申请内容不一致"); }
    public static ServiceException unconfirmed() { return error(6, "请先通过可信会话确认当前申请"); }
    public static ServiceException quota() { return error(7, "体验名额已满，请联系运营人员"); }
}
