package cn.iocoder.yudao.module.crm.service.trial;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.system.dal.dataobject.oauth2.*;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.dal.mysql.oauth2.*;
import cn.iocoder.yudao.module.system.dal.redis.oauth2.OAuth2AccessTokenRedisDAO;
import cn.iocoder.yudao.module.system.service.oauth2.*;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the production adapter against mocked core service/storage boundaries. */
class MgsTrialOAuthGatewayTest {
    OAuth2TokenService tokens = mock(OAuth2TokenService.class);
    OAuth2ClientService clients = mock(OAuth2ClientService.class);
    OAuth2AccessTokenMapper accesses = mock(OAuth2AccessTokenMapper.class);
    OAuth2RefreshTokenMapper refreshes = mock(OAuth2RefreshTokenMapper.class);
    AdminUserService users = mock(AdminUserService.class);
    OAuth2AccessTokenRedisDAO cache = mock(OAuth2AccessTokenRedisDAO.class);
    MgsTrialOAuthGateway gateway = new MgsTrialOAuthGateway(tokens, clients, accesses, refreshes, users, cache);
    OAuth2RefreshTokenDO refresh;
    @BeforeEach void setup() {
        when(users.getUser(100L)).thenReturn(new AdminUserDO().setId(100L).setStatus(0));
        when(clients.validOAuthClientFromCache("trial-client")).thenReturn(new OAuth2ClientDO()
                .setClientId("trial-client").setScopes(List.of("mgs.trial")).setAccessTokenValiditySeconds(600));
        refresh = new OAuth2RefreshTokenDO().setId(88L).setUserId(100L).setUserType(2).setClientId("trial-client")
                .setRefreshToken("fixture-refresh").setExpiresTime(LocalDateTime.now().plusDays(1));
        when(refreshes.selectById(88L)).thenReturn(refresh);
    }
    @Test void usesExistingAccessTokenWithoutDuplicatingTheGrant() {
        when(accesses.selectListByRefreshToken("fixture-refresh")).thenReturn(List.of(new OAuth2AccessTokenDO()
                .setAccessToken("fixture-access").setExpiresTime(LocalDateTime.now().plusMinutes(5))));
        assertEquals("fixture-access", gateway.resolve(100, 88, "trial-client").accessToken());
        verifyNoInteractions(tokens);
    }
    @Test void expiredAccessIsRenewedUsingTheExistingRefreshGrant() {
        when(accesses.selectListByRefreshToken("fixture-refresh")).thenReturn(List.of());
        when(tokens.refreshAccessToken("fixture-refresh", "trial-client")).thenReturn(new OAuth2AccessTokenDO()
                .setAccessToken("fixture-renewed").setExpiresTime(LocalDateTime.now().plusMinutes(5)));
        assertEquals("fixture-renewed", gateway.resolve(100, 88, "trial-client").accessToken());
        verify(tokens, never()).createAccessToken(anyLong(), anyInt(), anyString(), anyList());
    }
    @Test void missingForeignExpiredAndDisabledUserGrantsFailClosed() {
        refresh.setUserId(101L);
        assertThrows(ServiceException.class, () -> gateway.resolve(100, 88, "trial-client"));
        refresh.setUserId(100L).setExpiresTime(LocalDateTime.now().minusSeconds(1));
        assertThrows(ServiceException.class, () -> gateway.resolve(100, 88, "trial-client"));
        when(refreshes.selectById(88L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> gateway.resolve(100, 88, "trial-client"));
        when(users.getUser(100L)).thenReturn(new AdminUserDO().setStatus(1));
        assertThrows(ServiceException.class, () -> gateway.resolve(100, 88, "trial-client"));
        verifyNoInteractions(tokens);
    }
    @Test void revokesRefreshOnlySessionsAndCachedRefreshBearerAliases() {
        when(refreshes.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(refresh));
        gateway.revoke(100);
        verify(tokens).removeAccessToken(100L, 2);
        verify(refreshes).deleteById(88L);
        verify(cache).delete("fixture-refresh");
    }
}
