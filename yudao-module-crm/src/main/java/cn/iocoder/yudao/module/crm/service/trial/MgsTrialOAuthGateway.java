package cn.iocoder.yudao.module.crm.service.trial;

import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.module.system.dal.dataobject.oauth2.OAuth2AccessTokenDO;
import cn.iocoder.yudao.module.system.dal.dataobject.oauth2.OAuth2RefreshTokenDO;
import cn.iocoder.yudao.module.system.dal.redis.oauth2.OAuth2AccessTokenRedisDAO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import cn.iocoder.yudao.module.system.dal.mysql.oauth2.OAuth2AccessTokenMapper;
import cn.iocoder.yudao.module.system.dal.mysql.oauth2.OAuth2RefreshTokenMapper;
import cn.iocoder.yudao.module.system.service.oauth2.OAuth2ClientService;
import cn.iocoder.yudao.module.system.service.oauth2.OAuth2TokenService;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

@Service
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
@RequiredArgsConstructor
public class MgsTrialOAuthGateway implements TrialOAuthGateway {
    private final OAuth2TokenService tokens;
    private final OAuth2ClientService clients;
    private final OAuth2AccessTokenMapper accessTokens;
    private final OAuth2RefreshTokenMapper refreshTokens;
    private final AdminUserService users;
    private final OAuth2AccessTokenRedisDAO cache;

    private void validate(long userId, String clientId) {
        var user = users.getUser(userId);
        if (user == null || !Integer.valueOf(0).equals(user.getStatus())) { throw TrialException.error(11, "体验账号授权已停用"); }
        var client = clients.validOAuthClientFromCache(clientId);
        if (client.getScopes() == null || !client.getScopes().contains("mgs.trial")
                || client.getAccessTokenValiditySeconds() == null || client.getAccessTokenValiditySeconds() < 60
                || client.getAccessTokenValiditySeconds() > 1800) { throw TrialException.unavailable(); }
    }

    @Override public void revoke(long userId) {
        // Capture refresh-only sessions too: expired access rows may already have been cleaned up.
        var refreshRows = refreshTokens.selectList(new LambdaQueryWrapper<OAuth2RefreshTokenDO>()
                .eq(OAuth2RefreshTokenDO::getUserId, userId)
                .eq(OAuth2RefreshTokenDO::getUserType, UserTypeEnum.ADMIN.getValue()));
        tokens.removeAccessToken(userId, UserTypeEnum.ADMIN.getValue());
        for (var refresh : refreshRows) {
            refreshTokens.deleteById(refresh.getId());
            cache.delete(refresh.getRefreshToken()); // Existing framework accepts/caches refresh tokens as bearer aliases.
        }
    }

    @Override public long create(long userId, String clientId) {
        validate(userId, clientId);
        var access = tokens.createAccessToken(userId, UserTypeEnum.ADMIN.getValue(), clientId, List.of("mgs.trial"));
        var refresh = refreshTokens.selectByRefreshToken(access.getRefreshToken());
        if (refresh == null) { throw TrialException.error(11, "体验授权未能持久化"); }
        return refresh.getId();
    }

    @Override public Credential resolve(long userId, long refreshTokenId, String clientId) {
        validate(userId, clientId);
        var refresh = refreshTokens.selectById(refreshTokenId);
        if (refresh == null || !Objects.equals(refresh.getUserId(), userId)
                || !Objects.equals(refresh.getUserType(), UserTypeEnum.ADMIN.getValue())
                || !Objects.equals(refresh.getClientId(), clientId)
                || !refresh.getExpiresTime().isAfter(LocalDateTime.now())) {
            // Revocation is authoritative; a retry must never silently recreate a removed grant.
            throw TrialException.error(11, "体验授权已失效，需要重新验证授权");
        }
        OAuth2AccessTokenDO token = accessTokens.selectListByRefreshToken(refresh.getRefreshToken()).stream()
                .filter(t -> t.getExpiresTime().isAfter(LocalDateTime.now().plusSeconds(30)))
                .findFirst().orElseGet(() -> tokens.refreshAccessToken(refresh.getRefreshToken(), clientId));
        return new Credential(token.getAccessToken(), token.getExpiresTime().atZone(ZoneId.systemDefault()).toInstant().toString());
    }
}
