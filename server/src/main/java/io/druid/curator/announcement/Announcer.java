/*
 * Druid - a distributed column store.
 * Copyright (C) 2012, 2013  Metamarkets Group Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package io.druid.curator.announcement;

import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.collect.MapMaker;
import com.google.common.collect.Sets;
import com.metamx.common.IAE;
import com.metamx.common.ISE;
import com.metamx.common.Pair;
import com.metamx.common.guava.CloseQuietly;
import com.metamx.common.lifecycle.LifecycleStart;
import com.metamx.common.lifecycle.LifecycleStop;
import com.metamx.common.logger.Logger;
import io.druid.curator.ShutdownNowIgnoringExecutorService;
import io.druid.curator.cache.PathChildrenCacheFactory;
import io.druid.curator.cache.SimplePathChildrenCacheFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.utils.ZKPaths;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Announces things on Zookeeper.
 */
public class Announcer
{
  private static final Logger log = new Logger(Announcer.class);

  private final CuratorFramework curator;
  private final PathChildrenCacheFactory factory;

  private final List<Pair<String, byte[]>> toAnnounce = Lists.newArrayList();
  private final List<Pair<String, byte[]>> toUpdate = Lists.newArrayList();
  private final ConcurrentMap<String, PathChildrenCache> listeners = new MapMaker().makeMap();
  private final ConcurrentMap<String, ConcurrentMap<String, byte[]>> announcements = new MapMaker().makeMap();
  private final List<String> parentsIBuilt = new CopyOnWriteArrayList<String>();

  private boolean started = false;

  public Announcer(
      CuratorFramework curator,
      ExecutorService exec
  )
  {
    this.curator = curator;
    this.factory = new SimplePathChildrenCacheFactory(false, true, new ShutdownNowIgnoringExecutorService(exec));
  }

  @LifecycleStart
  public void start()
  {
    synchronized (toAnnounce) {
      if (started) {
        return;
      }

      started = true;

      for (Pair<String, byte[]> pair : toAnnounce) {
        announce(pair.lhs, pair.rhs);
      }
      toAnnounce.clear();

      for (Pair<String, byte[]> pair : toUpdate) {
        update(pair.lhs, pair.rhs);
      }
      toUpdate.clear();
    }
  }

  @LifecycleStop
  public void stop()
  {
    synchronized (toAnnounce) {
      if (!started) {
        return;
      }

      started = false;

      for (Map.Entry<String, PathChildrenCache> entry : listeners.entrySet()) {
        CloseQuietly.close(entry.getValue());
      }

      for (Map.Entry<String, ConcurrentMap<String, byte[]>> entry : announcements.entrySet()) {
        String basePath = entry.getKey();

        for (String announcementPath : entry.getValue().keySet()) {
          unannounce(ZKPaths.makePath(basePath, announcementPath));
        }
      }

      for (String parent : parentsIBuilt) {
        try {
          curator.delete().forPath(parent);
        }
        catch (Exception e) {
          log.info(e, "Unable to delete parent[%s], boooo.", parent);
        }
      }
    }
  }

  /**
   * Announces the provided bytes at the given path.  Announcement means that it will create an ephemeral node
   * and monitor it to make sure that it always exists until it is unannounced or this object is closed.
   *
   * @param path  The path to announce at
   * @param bytes The payload to announce
   */
  public void announce(String path, byte[] bytes)
  {
    synchronized (toAnnounce) {
      if (!started) {
        toAnnounce.add(Pair.of(path, bytes));
        return;
      }
    }

    final ZKPaths.PathAndNode pathAndNode = ZKPaths.getPathAndNode(path);

    final String parentPath = pathAndNode.getPath();
    boolean buildParentPath = false;

    ConcurrentMap<String, byte[]> subPaths = announcements.get(parentPath);

    if (subPaths == null) {
      try {
        if (curator.checkExists().forPath(parentPath) == null) {
          buildParentPath = true;
        }
      }
      catch (Exception e) {
        log.debug(e, "Problem checking if the parent existed, ignoring.");
      }

      // I don't have a watcher on this path yet, create a Map and start watching.
      announcements.putIfAbsent(parentPath, new MapMaker().<String, byte[]>makeMap());

      // Guaranteed to be non-null, but might be a map put in there by another thread.
      final ConcurrentMap<String, byte[]> finalSubPaths = announcements.get(parentPath);

      // Synchronize to make sure that I only create a listener once.
      synchronized (finalSubPaths) {
        if (!listeners.containsKey(parentPath)) {
          final PathChildrenCache cache = factory.make(curator, parentPath);
          cache.getListenable().addListener(
              new PathChildrenCacheListener()
              {
                private final AtomicReference<Set<String>> pathsLost = new AtomicReference<Set<String>>(null);

                @Override
                public void childEvent(CuratorFramework client, PathChildrenCacheEvent event) throws Exception
                {
                  log.debug("Path[%s] got event[%s]", parentPath, event);
                  switch (event.getType()) {
                    case CHILD_REMOVED:
                      final ChildData child = event.getData();
                      final ZKPaths.PathAndNode childPath = ZKPaths.getPathAndNode(child.getPath());
                      final byte[] value = finalSubPaths.get(childPath.getNode());
                      if (value != null) {
                        log.info("Node[%s] dropped, reinstating.", child.getPath());
                        createAnnouncement(child.getPath(), value);
                      }
                      break;
                    case CONNECTION_LOST:
                      // Lost connection, which means session is broken, take inventory of what has been seen.
                      // This is to protect from a race condition in which the ephemeral node could have been
                      // created but not actually seen by the PathChildrenCache, which means that it won't know
                      // that it disappeared and thus will not generate a CHILD_REMOVED event for us.  Under normal
                      // circumstances, this can only happen upon connection loss; but technically if you have
                      // an adversary in the system, they could also delete the ephemeral node before the cache sees
                      // it.  This does not protect from that case, so don't have adversaries.

                      Set<String> pathsToReinstate = Sets.newHashSet();
                      for (String node : finalSubPaths.keySet()) {
                        String path = ZKPaths.makePath(parentPath, node);
                        log.info("Node[%s] is added to reinstate.", path);
                        pathsToReinstate.add(path);
                      }

                      if (!pathsToReinstate.isEmpty() && !pathsLost.compareAndSet(null, pathsToReinstate)) {
                        log.info("Already had a pathsLost set!?[%s]", parentPath);
                      }
                      break;
                    case CONNECTION_RECONNECTED:
                      final Set<String> thePathsLost = pathsLost.getAndSet(null);

                      if (thePathsLost != null) {
                        for (String path : thePathsLost) {
                          log.info("Reinstating [%s]", path);
                          final ZKPaths.PathAndNode split = ZKPaths.getPathAndNode(path);
                          createAnnouncement(path, announcements.get(split.getPath()).get(split.getNode()));
                        }
                      }
                      break;
                  }
                }
              }
          );

          synchronized (toAnnounce) {
            if (started) {
              if (buildParentPath) {
                createPath(parentPath);
              }
              startCache(cache);
              listeners.put(parentPath, cache);
            }
          }
        }
      }

      subPaths = finalSubPaths;
    }

    boolean created = false;
    synchronized (toAnnounce) {
      if (started) {
        byte[] oldBytes = subPaths.putIfAbsent(pathAndNode.getNode(), bytes);

        if (oldBytes == null) {
          created = true;
        } else if (!Arrays.equals(oldBytes, bytes)) {
          throw new IAE("Cannot reannounce different values under the same path");
        }
      }
    }

    if (created) {
      try {
        createAnnouncement(path, bytes);
      }
      catch (Exception e) {
        throw Throwables.propagate(e);
      }
    }
  }

  public void update(final String path, final byte[] bytes)
  {
    synchronized (toAnnounce) {
      if (!started) {
        toUpdate.add(Pair.of(path, bytes));
        return;
      }
    }

    final ZKPaths.PathAndNode pathAndNode = ZKPaths.getPathAndNode(path);

    final String parentPath = pathAndNode.getPath();
    final String nodePath = pathAndNode.getNode();

    ConcurrentMap<String, byte[]> subPaths = announcements.get(parentPath);

    if (subPaths == null || subPaths.get(nodePath) == null) {
      throw new ISE("Cannot update a path[%s] that hasn't been announced!", path);
    }

    synchronized (toAnnounce) {
      try {
        byte[] oldBytes = subPaths.get(nodePath);

        if (!Arrays.equals(oldBytes, bytes)) {
          subPaths.put(nodePath, bytes);
          updateAnnouncement(path, bytes);
        }
      }
      catch (Exception e) {
        throw Throwables.propagate(e);
      }
    }
  }

  private String createAnnouncement(final String path, byte[] value) throws Exception
  {
    return curator.create().compressed().withMode(CreateMode.EPHEMERAL).inBackground().forPath(path, value);
  }

  private Stat updateAnnouncement(final String path, final byte[] value) throws Exception
  {
    return curator.setData().compressed().inBackground().forPath(path, value);
  }

  /**
   * Unannounces an announcement created at path.  Note that if all announcements get removed, the Announcer
   * will continue to have ZK watches on paths because clearing them out is a source of ugly race conditions.
   * <p/>
   * If you need to completely clear all the state of what is being watched and announced, stop() the Announcer.
   *
   * @param path
   */
  public void unannounce(String path)
  {
    log.info("unannouncing [%s]", path);
    final ZKPaths.PathAndNode pathAndNode = ZKPaths.getPathAndNode(path);
    final String parentPath = pathAndNode.getPath();

    final ConcurrentMap<String, byte[]> subPaths = announcements.get(parentPath);

    if (subPaths == null || subPaths.remove(pathAndNode.getNode()) == null) {
      log.error("Path[%s] not announced, cannot unannounce.", path);
      return;
    }

    try {
      curator.delete().guaranteed().forPath(path);
    }
    catch (KeeperException.NoNodeException e) {
      log.info("node[%s] didn't exist anyway...", path);
    }
    catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  private void startCache(PathChildrenCache cache)
  {
    try {
      cache.start();
    }
    catch (Exception e) {
      CloseQuietly.close(cache);
      throw Throwables.propagate(e);
    }
  }

  private void createPath(String parentPath)
  {
    try {
      curator.create().creatingParentsIfNeeded().forPath(parentPath);
      parentsIBuilt.add(parentPath);
    }
    catch (Exception e) {
      log.info(e, "Problem creating parentPath[%s], someone else created it first?", parentPath);
    }
  }
}
