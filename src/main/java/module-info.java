/**
 * Java ZBDD Library - Zero-Suppressed Binary Decision Diagrams implementation.
 *
 * @see <a href="https://en.wikipedia.org/wiki/Zero-suppressed_decision_diagram">
 *      Zero-Suppressed Binary Decision Diagrams on Wikipedia</a>
 */
module de.sayayi.lib.zbdd {

  requires static org.jetbrains.annotations;

  exports de.sayayi.lib.zbdd;
  exports de.sayayi.lib.zbdd.cache;
  exports de.sayayi.lib.zbdd.exception;

}