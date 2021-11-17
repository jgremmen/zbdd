package de.sayayi.lib.zbdd;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Range;

import java.util.Arrays;

import static java.lang.Integer.MAX_VALUE;
import static java.util.stream.Collectors.joining;


public interface ZbddNameResolver
{
  default @NotNull String getEmptyName() {
    return "{}";
  }


  default @NotNull String getBaseName() {
    return "{{}}";
  }


  @NotNull String getVariable(@Range(from = 1, to = MAX_VALUE) int var);


  default @NotNull String getCube(int @NotNull [] vars)
  {
    return vars.length == 0
        ? getEmptyName()
        : Arrays.stream(vars).sorted().mapToObj(this::getVariable).collect(joining("."));
  }
}
