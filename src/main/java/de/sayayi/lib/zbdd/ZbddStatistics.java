package de.sayayi.lib.zbdd;


public interface ZbddStatistics
{
  int getNodeTableSize();


  int getFreeNodes();


  int getDeadNodes();


  default int getAvailableNodes() {
    return getFreeNodes() + getDeadNodes();
  }


  default int getOccupiedNodes() {
    return getNodeTableSize() - getAvailableNodes();
  }


  int getNodeLookups();


  int getNodeLookupHitCount();


  default double getNodeLookupHitRatio() {
    return getNodeLookupHitCount() / (double)getNodeLookups();
  }


  default double getNodeLookupMissRatio() {
    return 1.0 - getNodeLookupHitRatio();
  }


  int getGCCount();


  long getGCFreedNodes();


  long getMemoryUsage();


  int getRegisteredVars();
}
