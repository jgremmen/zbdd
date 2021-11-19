package de.sayayi.lib.zbdd;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Range;

import static de.sayayi.lib.zbdd.Zbdd.MAX_NODES;


public interface ZbddNodesAdvisor
{
  @Contract(pure = true)
  @Range(from = 4, to = MAX_NODES)
  int getInitialNodes();


  @Contract(pure = true)
  @Range(from = 1, to = MAX_NODES)
  int getMinimumFreeNodes(@NotNull ZBDDStatistics statistics);


  @Contract(pure = true)
  @Range(from = 1, to = MAX_NODES)
  int adviseNodesGrowth(@NotNull ZBDDStatistics statistics);


  @Contract(pure = true)
  boolean isGCRequired(@NotNull ZBDDStatistics statistics);
}
