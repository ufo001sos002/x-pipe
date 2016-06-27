package com.ctrip.xpipe.redis.console.dao.file;


import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.unidal.tuple.Pair;
import org.xml.sax.SAXException;

import com.ctrip.xpipe.redis.console.dao.AbstractMetaDao;
import com.ctrip.xpipe.redis.console.dao.DaoException;
import com.ctrip.xpipe.redis.core.entity.ClusterMeta;
import com.ctrip.xpipe.redis.core.entity.DcMeta;
import com.ctrip.xpipe.redis.core.entity.KeeperMeta;
import com.ctrip.xpipe.redis.core.entity.MetaServerMeta;
import com.ctrip.xpipe.redis.core.entity.RedisMeta;
import com.ctrip.xpipe.redis.core.entity.ShardMeta;
import com.ctrip.xpipe.redis.core.entity.XpipeMeta;
import com.ctrip.xpipe.redis.core.entity.ZkServerMeta;
import com.ctrip.xpipe.redis.core.transform.DefaultSaxParser;

/**
 * @author wenchao.meng
 *
 * Jun 23, 2016
 */
@Component
public class DefaultFileDao extends AbstractMetaDao{
	
	private String fileName = null;
	private XpipeMeta xpipeMeta;
	
	public DefaultFileDao(){
		this("../../../redis-integrated-test/src/main/resources/integrated-test.xml");
	}
	
	public DefaultFileDao(String fileName) {
		this.fileName = fileName;
		load(fileName);
	}

	public void load(String fileName) {
		try {
			URL url = getClass().getClassLoader().getResource(".");
			File file = new File(new File(url.getPath()), fileName);
			logger.info("[load]{}", file);
			xpipeMeta = DefaultSaxParser.parse(new FileInputStream(file));
		} catch (SAXException | IOException e) {
			logger.error("[load]" + fileName, e);
			throw new IllegalStateException("load " + fileName + " failed!", e);
		}
	}

	@Override
	public XpipeMeta getXpipeMeta() {
		return this.xpipeMeta;
	}

	@Override
	public DcMeta getDcMeta(String dc) {
		return this.xpipeMeta.getDcs().get(dc);
	}

	@Override
	public ClusterMeta getClusterMeta(String dc, String clusterId) {
		DcMeta dcMeta = getDcMeta(dc);
		if(dcMeta == null){
			return null;
		}
		return dcMeta.getClusters().get(clusterId);
	}

	@Override
	public ShardMeta getShardMeta(String dc, String clusterId, String shardId) {
		
		ClusterMeta clusterMeta = getClusterMeta(dc, clusterId);
		if(clusterMeta == null){
			return null;
		}
		return clusterMeta.getShards().get(shardId);
	}

	@Override
	public List<KeeperMeta> getKeepers(String dc, String clusterId, String shardId) {
		ShardMeta shardMeta = getShardMeta(dc, clusterId, shardId);
		if(shardMeta == null){
			return null;
		}
		return shardMeta.getKeepers();
	}

	@Override
	public List<RedisMeta> getRedises(String dc, String clusterId, String shardId) {
		
		ShardMeta shardMeta = getShardMeta(dc, clusterId, shardId);
		if(shardMeta == null){
			return null;
		}
		return shardMeta.getRedises();
	}

	@Override
	public KeeperMeta getKeeperActive(String dc, String clusterId, String shardId) {
		List<KeeperMeta> keepers = getKeepers(dc, clusterId, shardId);
		if(keepers == null){
			return null;
		}
		
		for(KeeperMeta keeperMeta : keepers){
			if(keeperMeta.isActive()){
				return keeperMeta;
			}
		}
		return null;
	}

	@Override
	public List<KeeperMeta> getKeeperBackup(String dc, String clusterId, String shardId) {
		
		List<KeeperMeta> keepers = getKeepers(dc, clusterId, shardId);
		if(keepers == null){
			return null;
		}
		
		List<KeeperMeta> result = new LinkedList<>();
		for(KeeperMeta keeperMeta : keepers){
			if(!keeperMeta.isActive()){
				result.add(keeperMeta);
			}
		}
		return result;
	}

	@Override
	public Pair<String, RedisMeta> getRedisMaster(String clusterId, String shardId) {
		
		for(DcMeta dcMeta : xpipeMeta.getDcs().values()){
			for(ClusterMeta clusterMeta : dcMeta.getClusters().values()){
				if(!clusterId.equals(clusterMeta.getId())){
					continue;
				}
				for(ShardMeta shardMeta : clusterMeta.getShards().values()){
					if(!shardId.equals(shardMeta.getId())){
						continue;
					}
					for(RedisMeta redisMeta : shardMeta.getRedises()){
						if(redisMeta.isMaster()){
							return new Pair<String, RedisMeta>(dcMeta.getId(), redisMeta);
						}
					}
				}
			}
		}
		return null;
	}

	@Override
	public boolean updateKeeperActive(String dc, String clusterId, String shardId, KeeperMeta activeKeeper) throws DaoException {
		
		if(!valid(activeKeeper)){
			logger.info("[updateKeeperActive][keeper information unvalid]{}", activeKeeper);
		}
		
		ShardMeta shardMeta = getShardMeta(dc, clusterId, shardId);
		if(shardMeta == null){
			throw new DaoException(String.format("unfound keepers: %s %s %s", dc, clusterId, shardId));
		}
		List<KeeperMeta> keepers = shardMeta.getKeepers();
		boolean found = false;
		boolean changed = false;
		for(KeeperMeta keeperMeta : keepers){
			if(keeperMeta.getIp().equals(activeKeeper.getIp()) && keeperMeta.getPort().equals(activeKeeper.getPort())){
				found = true;
				if(!keeperMeta.isActive()){
					logger.info("[updateKeeperActive][set keeper active]{}", keeperMeta);
					keeperMeta.setActive(true);
					changed = true;
				}
			}else{
				if(keeperMeta.isActive()){
					logger.info("[updateKeeperActive][set keeper unactive]{}", keeperMeta);
					keeperMeta.setActive(false);
					changed = true;
				}
			}
		}
		if(!found && valid(activeKeeper)){
			changed = true;
			activeKeeper.setActive(true);
			activeKeeper.setParent(shardMeta);
			keepers.add(activeKeeper);
		}
		return changed;
	}
	
	private boolean valid(KeeperMeta activeKeeper) {
		
		if(activeKeeper.getIp() == null || activeKeeper.getPort() == null){
			return false;
		}
		return true;
	}

	public String getFileName() {
		return fileName;
	}

	@Override
	public List<MetaServerMeta> getMetaServers(String dc) {
		
		DcMeta dcMeta = getDcMeta(dc);
		if( dcMeta == null ){
			return null;
		}
		return dcMeta.getMetaServers();
	}

	@Override
	public ZkServerMeta getZkServerMeta(String dc) {
		
		DcMeta dcMeta = getDcMeta(dc);
		if( dcMeta == null ){
			return null;
		}
		return dcMeta.getZkServer();
	}

	@Override
	public Set<String> getDcs() {
		return xpipeMeta.getDcs().keySet();
	}

	@Override
	public boolean updateRedisMaster(String dc, String clusterId, String shardId, RedisMeta redisMaster) throws DaoException {
		
		String activeDc = getActiveDc(clusterId);
		if(!activeDc.equals(dc)){
			throw new DaoException("active dc:" + activeDc + ", but given:" + dc + ", clusterID:" + clusterId);
		}
		ShardMeta shardMeta = getShardMeta(dc, clusterId, shardId);
		if(shardMeta == null){
			throw new DaoException(String.format("unfound shard %s,%s,%s", dc, clusterId, shardId));
		}
		
		boolean found = false, changed = false;
		
		for(RedisMeta redisMeta : shardMeta.getRedises()){
			if(redisMeta.getIp().equals(redisMaster.getIp()) && redisMeta.getPort().equals(redisMaster.getPort())){
				found = true;
				if(!redisMeta.isMaster()){
					logger.info("[updateRedisMaster][change redis to master]{}", redisMeta);
					redisMeta.setMaster(true);
					changed = true;
				}else{
					logger.info("[updateRedisMaster][redis already master]{}", redisMeta);
				}
			}else{
				if(redisMeta.isMaster()){
					logger.info("[updateRedisMaster][change redis to slave]{}", redisMeta);
					redisMeta.setMaster(false);
					changed = true;
				}
			}
		}

		if(!found){
			redisMaster.setMaster(true);
			redisMaster.setParent(shardMeta);
			shardMeta.getRedises().add(redisMaster);
			changed = true;
		}
		return changed;
	}

	@Override
	public String getActiveDc(String clusterId) throws DaoException {
		
		for(DcMeta dcMeta : xpipeMeta.getDcs().values()){
			ClusterMeta clusterMeta = dcMeta.getClusters().get(clusterId);
			if(clusterMeta == null){
				continue;
			}
			return clusterMeta.getActiveDc();
		}
		throw new DaoException("clusterId " + clusterId + " not found!");
	}

	@Override
	public boolean updateActiveDc(String clusterId, String activeDc) throws DaoException {
		
		logger.info("[updateActiveDc]{}, {}", clusterId, activeDc);
		String currentActive = getActiveDc(clusterId);
		if(currentActive.equals(activeDc)){
			logger.info("[updateActiveDc][not changed]{}, {}", clusterId, activeDc);
			return false;
		}
		
		for(ClusterMeta clusterMeta : getClusterMetaInAllDc(clusterId)){
			clusterMeta.setActiveDc(activeDc);
		}
		return true;
	}

	private List<ClusterMeta> getClusterMetaInAllDc(String clusterId) {
		
		List<ClusterMeta> result = new LinkedList<>();
		for(DcMeta dcMeta : getXpipeMeta().getDcs().values()){
			ClusterMeta clusterMeta = dcMeta.getClusters().get(clusterId);
			if(clusterMeta == null){
				continue;
			}
			result.add(clusterMeta);
		}
		return result;
	}

}
