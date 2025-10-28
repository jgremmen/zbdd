/*
 * Copyright 2025 Jeroen Gremmen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.sayayi.lib.zbdd.impl;

import de.sayayi.lib.zbdd.Zbdd;
import de.sayayi.lib.zbdd.Zbdd.WithCache;
import de.sayayi.lib.zbdd.ZbddCapacityAdvisor;
import de.sayayi.lib.zbdd.cache.ZbddCache;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.MustBeInvokedByOverriders;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Range;

import static de.sayayi.lib.zbdd.cache.ZbddCache.Operation1.*;
import static de.sayayi.lib.zbdd.cache.ZbddCache.Operation2.*;
import static java.lang.Integer.MIN_VALUE;


/**
 * Extension of the {@link Zbdd} class to add support for caching results.
 *
 * @author Jeroen Gremmen
 * @since 0.5.0
 */
@SuppressWarnings("DuplicatedCode")
public class ZbddCachedImpl extends ZbddImpl implements WithCache
{
  private final ZbddCache zbddCache;


  public ZbddCachedImpl(@NotNull ZbddCapacityAdvisor capacityAdvisor, @NotNull ZbddCache zbddCache)
  {
    super(capacityAdvisor);

    this.zbddCache = zbddCache;
  }


  public ZbddCachedImpl(@NotNull ZbddImpl zbdd, @NotNull ZbddCache zbddCache)
  {
    super(zbdd);

    this.zbddCache = zbddCache;
  }


  @Override
  public @NotNull ZbddCache getZbddCache() {
    return zbddCache;
  }


  @Override
  @Contract(pure = true)
  public @NotNull ZbddCachedImpl clone() {
    throw new UnsupportedOperationException();
  }


  /**
   * {@inheritDoc}
   *
   * If a zbdd cache is assigned, it will be cleared as well.
   */
  @Override
  @Contract(mutates = "this")
  @MustBeInvokedByOverriders
  public void clear()
  {
    if (zbddCache != null)
      zbddCache.clear();

    super.clear();
  }


  /**
   * @since 0.1.3
   */
  @Override
  @Contract(mutates = "this")
  protected int __subset0(int zbdd, int var)
  {
    final int top = __getVar(zbdd);

    if (top < var)
      return zbdd;

    if (top == var)
      return getP0(zbdd);

    int r = zbddCache.getResult(SUBSET0, zbdd, var);
    if (r == MIN_VALUE)
      zbddCache.putResult(SUBSET0, zbdd, var, r = super.__subset0(zbdd, var));

    return r;
  }


  /**
   * @since 0.1.3
   */
  @Override
  @Contract(mutates = "this")
  protected int __subset1(int zbdd, int var)
  {
    final int top = __getVar(zbdd);

    if (top < var)
      return ZBDD_EMPTY;

    if (top == var)
      return getP1(zbdd);

    int r = zbddCache.getResult(SUBSET1, zbdd, var);
    if (r == MIN_VALUE)
      zbddCache.putResult(SUBSET1, zbdd, var, r = super.__subset1(zbdd, var));

    return r;
  }


  /**
   * @since 0.1.3
   */
  @Override
  @Contract(mutates = "this")
  protected int __change(int zbdd, int var)
  {
    final int top = __getVar(zbdd);

    if (top < var)
      return getNode(var, ZBDD_EMPTY, zbdd);

    int r = zbddCache.getResult(CHANGE, zbdd, var);
    if (r == MIN_VALUE)
      zbddCache.putResult(CHANGE, zbdd, var, r = super.__change(zbdd, var));

    return r;
  }


  /**
   * @since 0.1.3
   */
  @Override
  @Contract(pure = true)
  protected int __count(int zbdd)
  {
    if (zbdd < 2)
      return zbdd;

    int r = zbddCache.getResult(COUNT, zbdd);
    if (r == MIN_VALUE)
      zbddCache.putResult(COUNT, zbdd, r = super.__count(zbdd));

    return r;
  }


  /**
   * @since 0.1.3
   */
  @Override
  @Contract(mutates = "this")
  protected int __union(int p, int q)
  {
    if (q == ZBDD_EMPTY || p == q)
      return p;
    if (p == ZBDD_EMPTY)
      return q;

    if (__getVar(p) > __getVar(q))
    {
      // swap p <-> q, p_var <-> q_var
      int tmp = p; p = q; q = tmp;
    }

    int r = zbddCache.getResult(UNION, p, q);
    if (r == MIN_VALUE)
      zbddCache.putResult(UNION, p, q, r = super.__union(p, q));

    return r;
  }


  /**
   * @since 0.1.3
   */
  @Override
  @Contract(mutates = "this")
  protected int __intersect(int p, int q)
  {
    if (p == ZBDD_EMPTY || q == ZBDD_EMPTY)
      return ZBDD_EMPTY;
    if (p == q)
      return p;

    int r = zbddCache.getResult(INTERSECT, p, q);
    if (r == MIN_VALUE)
      zbddCache.putResult(INTERSECT, p, q, r = super.__intersect(p, q));

    return r;
  }


  /**
   * @since 0.1.3
   */
  @Override
  @Contract(mutates = "this")
  protected int __difference(int p, int q)
  {
    if (p == ZBDD_EMPTY || p == q)
      return ZBDD_EMPTY;
    if (q == ZBDD_EMPTY)
      return p;

    int r = zbddCache.getResult(DIFFERENCE, p, q);
    if (r == MIN_VALUE)
      zbddCache.putResult(DIFFERENCE, p, q, r = super.__difference(p, q));

    return r;
  }


  /**
   * @since 0.1.3
   */
  @Override
  @Contract(mutates = "this")
  protected int __multiply(int p, int q)
  {
    if (p == ZBDD_EMPTY || q == ZBDD_EMPTY)
      return ZBDD_EMPTY;
    if (p == ZBDD_BASE)
      return q;
    if (q == ZBDD_BASE)
      return p;

    if (__getVar(p) > __getVar(q))
    {
      // swap p <-> q, p_var <-> q_var
      int tmp = p; p = q; q = tmp;
    }

    int r = zbddCache.getResult(MULTIPLY, p, q);
    if (r == MIN_VALUE)
      zbddCache.putResult(MULTIPLY, p, q, r = super.__multiply(p, q));

    return r;
  }


  /**
   * @since 0.1.3
   */
  @Override
  @Contract(mutates = "this")
  protected int __divide(int p, int q)
  {
    if (p < 2)
      return ZBDD_EMPTY;
    if (p == q)
      return ZBDD_BASE;
    if (q == ZBDD_BASE)
      return p;

    int r = zbddCache.getResult(DIVIDE, p, q);
    if (r == MIN_VALUE)
      zbddCache.putResult(DIVIDE, p, q, r = super.__divide(p, q));

    return r;
  }


  /**
   * @since 0.1.3
   */
  @Override
  @Contract(mutates = "this")
  protected int __modulo(int p, int q)
  {
    int r = zbddCache.getResult(MODULO, p, q);
    if (r == MIN_VALUE)
      zbddCache.putResult(MODULO, p, q, r = super.__modulo(p, q));

    return r;
  }


  /**
   * @since 0.1.3
   */
  @Override
  @Contract(mutates = "this")
  protected int __atomize(int zbdd)
  {
    if (zbdd < 2)
      return ZBDD_EMPTY;

    int r = zbddCache.getResult(ATOMIZE, zbdd);
    if (r == MIN_VALUE)
      zbddCache.putResult(ATOMIZE, zbdd, r = super.__atomize(zbdd));

    return r;
  }


  /**
   * @since 0.1.3
   */
  @Override
  @Contract(mutates = "this")
  protected int __removeBase(int zbdd)
  {
    if (zbdd < 2)
      return ZBDD_EMPTY;

    int r = zbddCache.getResult(REMOVE_BASE, zbdd);
    if (r == MIN_VALUE)
      zbddCache.putResult(REMOVE_BASE, zbdd, r = super.__removeBase(zbdd));

    return r;
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES - 2)
  @MustBeInvokedByOverriders
  @SuppressWarnings("UnusedReturnValue")
  public int gc()
  {
    if (zbddCache != null)
      zbddCache.clear();

    return super.gc();
  }
}
