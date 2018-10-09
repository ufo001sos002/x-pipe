package com.ctrip.xpipe.redis.console.healthcheck.redisconf.version;

import com.ctrip.xpipe.redis.console.alert.ALERT_TYPE;
import com.ctrip.xpipe.redis.console.alert.AlertManager;
import com.ctrip.xpipe.redis.console.healthcheck.RedisHealthCheckInstance;
import com.ctrip.xpipe.redis.console.healthcheck.RedisInstanceInfo;
import com.ctrip.xpipe.redis.console.healthcheck.redisconf.RedisConfigCheckAction;
import com.ctrip.xpipe.redis.console.healthcheck.session.Callbackable;
import com.ctrip.xpipe.redis.console.resources.MetaCache;
import com.ctrip.xpipe.redis.core.protocal.cmd.InfoResultExtractor;
import com.ctrip.xpipe.utils.StringUtil;
import com.ctrip.xpipe.utils.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/**
 * @author chen.zhu
 * <p>
 * Oct 07, 2018
 */
public class VersionCheckAction extends RedisConfigCheckAction {

    private static final Logger logger = LoggerFactory.getLogger(VersionCheckAction.class);

    private static final String XVERSION = "xredis_version";

    private String info;

    private MetaCache metaCache;

    public VersionCheckAction(ScheduledExecutorService scheduled, RedisHealthCheckInstance instance,
                              ExecutorService executors, AlertManager alertManager, MetaCache metaCache) {
        super(scheduled, instance, executors, alertManager);
        this.metaCache = metaCache;
    }

    @Override
    protected void doScheduledTask0() {
        RedisInstanceInfo instanceInfo = getActionInstance().getRedisInstanceInfo();
        if(!metaCache.inBackupDc(instanceInfo.getHostPort())) {
            checkPassed();
            return;
        }
        if(checkVersion()) {
            checkPassed();
            return;
        }
        getActionInstance().getRedisSession().infoServer(new Callbackable<String>() {
            @Override
            public void success(String message) {
                info = message;
            }

            @Override
            public void fail(Throwable throwable) {
                logger.error("[VersionCheckAction] redis: {}", getActionInstance().getRedisInstanceInfo(), throwable);
            }
        });
    }

    private boolean checkVersion() {
        if(info == null) {
            return false;
        }
        InfoResultExtractor extractor = new InfoResultExtractor(info);
        String version = extractor.extract(XVERSION);
        String targetVersion = getActionInstance().getHealthCheckConfig().getMinXRedisVersion();
        if(version == null || StringUtil.compareVersion(version, targetVersion) < 0) {
            String alertMessage = String.format("Redis %s should be XRedis 0.0.3 or above", getActionInstance().getEndpoint().toString());
            logger.warn("{}", alertMessage);
            alertManager.alert(getActionInstance().getRedisInstanceInfo(), ALERT_TYPE.XREDIS_VERSION_NOT_VALID, alertMessage);
            return false;
        }
        return true;
    }

    @VisibleForTesting
    protected VersionCheckAction setInfo(String info) {
        this.info = info;
        return this;
    }
}
