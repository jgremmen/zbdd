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
package de.sayayi.lib.zbdd.cache;

import de.sayayi.lib.zbdd.Zbdd;
import de.sayayi.lib.zbdd.ZbddCapacityAdvisor;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.MustBeInvokedByOverriders;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Range;

import static de.sayayi.lib.zbdd.cache.ZbddCache.Operation1.*;
import static de.sayayi.lib.zbdd.cache.ZbddCache.Operation2.*;
import static java.lang.Integer.MAX_VALUE;
import static java.lang.Integer.MIN_VALUE;


/**
 * Extension of the {@link Zbdd} class to add support for caching results.
 *
 * @author Jeroen Gremmen
 * @since 0.4.0
 */
@SuppressWarnings("DuplicatedCode")
public class ZbddCached extends Zbdd
{
  private transient ZbddCache zbddCache = null;


  public ZbddCached() {
    this(DefaultCapacityAdvisor.INSTANCE);
  }


  public ZbddCached(@NotNull ZbddCapacityAdvisor capacityAdvisor) {
    super(capacityAdvisor);
  }


  protected ZbddCached(@NotNull Zbdd zbdd) {
    super(zbdd);
  }


  /**
   * Sets or removes a zbdd cache.
   * <p>
   * The zbdd implementation without caching is already very fast. If the same operations on zbdds
   * are performed frequently, then adding a cache may help to improve performance. However, if the
   * operations performed are mostly unique (like the 8-queens problem), then adding a cache will
   * reduce the overall performance.
   * <p>
   * Make sure to test your zbdd operations with and without a cache to find out whether
   * adding a cache is going to improve performance or not.
   *
   * @param zbddCache  zbdd cache instance or {@code null} to remove a previously assigned cache
   *
   * @since 0.1.3
   */
  @Contract(mutates = "this,param1")
  public void setZbddCache(ZbddCache zbddCache)
  {
    this.zbddCache = zbddCache;

    if (zbddCache != null)
      zbddCache.clear();
  }


  /**
   * Returns the currently assigned zbdd cache implementation.
   *
   * @return  zbdd cache instance or {@code null} if no zbdd cache was assigned
   *
   * @since 0.1.3
   */
  @Contract(pure = true)
  public ZbddCache getZbddCache() {
    return zbddCache;
  }


  @Override
  @Contract(pure = true)
  @SuppressWarnings("MethodDoesntCallSuperMethod")
  public ZbddCached clone() {
    return new ZbddCached(this);
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


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int subset0(@Range(from = 0, to = MAX_NODES) int zbdd, @Range(from = 1, to = MAX_VALUE) int var)
  {
    checkZbdd(zbdd, "zbdd");
    checkVar(var);

    return zbddCache != null ? __subset0_cache(zbdd, var) : __subset0(zbdd, var);
  }


  /**
   * @since 0.1.3
   */
  @Contract(mutates = "this")
  protected final int __subset0_cache(int zbdd, int var)
  {
    final int top = getVar(zbdd);

    if (top < var)
      return zbdd;

    if (top == var)
      return getP0(zbdd);

    int r = zbddCache.getResult(SUBSET0, zbdd, var);
    if (r == MIN_VALUE)
    {
      __incRef(zbdd);

      final int p0 = __incRef(__subset0_cache(getP0(zbdd), var));
      final int p1 = __subset0_cache(getP1(zbdd), var);

      zbddCache.putResult(SUBSET0, zbdd, var, r = getNode(top, __decRef(p0), p1));

      __decRef(zbdd);
    }

    return r;
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int subset1(@Range(from = 0, to = MAX_NODES) int zbdd, @Range(from = 1, to = MAX_VALUE) int var)
  {
    checkZbdd(zbdd, "zbdd");
    checkVar(var);

    return zbddCache != null ? __subset1_cache(zbdd, var) : __subset1(zbdd, var);
  }


  /**
   * @since 0.1.3
   */
  @Contract(mutates = "this")
  protected final int __subset1_cache(int zbdd, int var)
  {
    final int top = getVar(zbdd);

    if (top < var)
      return ZBDD_EMPTY;

    if (top == var)
      return getP1(zbdd);

    int r = zbddCache.getResult(SUBSET1, zbdd, var);
    if (r == MIN_VALUE)
    {
      __incRef(zbdd);

      final int p0 = __incRef(__subset1_cache(getP0(zbdd), var));
      final int p1 = __subset1_cache(getP1(zbdd), var);

      zbddCache.putResult(SUBSET1, zbdd, var, r = getNode(top, __decRef(p0), p1));

      __decRef(zbdd);
    }

    return r;
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int change(@Range(from = 0, to = MAX_NODES) int zbdd, @Range(from = 1, to = MAX_VALUE) int var)
  {
    checkZbdd(zbdd, "zbdd");
    checkVar(var);

    return zbddCache != null ? __change_cache(zbdd, var) : __change(zbdd, var);
  }


  /**
   * @since 0.1.3
   */
  @Contract(mutates = "this")
  protected final int __change_cache(int zbdd, int var)
  {
    final int top = getVar(zbdd);

    if (top < var)
      return getNode(var, ZBDD_EMPTY, zbdd);

    int r = zbddCache.getResult(CHANGE, zbdd, var);
    if (r == MIN_VALUE)
    {
      __incRef(zbdd);

      if (top == var)
        r = getNode(var, getP1(zbdd), getP0(zbdd));
      else
      {
        final int p0 = __incRef(__change_cache(getP0(zbdd), var));
        final int p1 = __change_cache(getP1(zbdd), var);

        r = getNode(top, __decRef(p0), p1);
      }

      zbddCache.putResult(CHANGE, zbdd, var, r);

      __decRef(zbdd);
    }

    return r;
  }


  @Contract(pure = true)
  @Range(from = 0, to = MAX_NODES + 1)
  public int count(@Range(from = 0, to = MAX_NODES) int zbdd)
  {
    checkZbdd(zbdd, "zbdd");

    return zbddCache != null ? __count_cache(zbdd) : __count(zbdd);
  }


  /**
   * @since 0.1.3
   */
  @Contract(pure = true)
  protected final int __count_cache(int zbdd)
  {
    if (zbdd < 2)
      return zbdd;

    int r = zbddCache.getResult(COUNT, zbdd);
    if (r == MIN_VALUE)
    {
      r = __count(zbdd);

      zbddCache.putResult(COUNT, zbdd, r);
    }

    return r;
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int union(int... p)
  {
    for(var pn: p)
      __incRef(checkZbdd(pn, "p"));

    var r = p[0];

    for(int i = 1, n = p.length; i < n; i++)
    {
      final var pi = p[i];

      r = zbddCache != null ? __union_cache(r, pi) : __union(r, pi);
    }

    for(var pn: p)
      __decRef(pn);

    return r;
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int union(@Range(from = 0, to = MAX_NODES) int p, @Range(from = 0, to = MAX_NODES) int q)
  {
    checkZbdd(p, "p");
    checkZbdd(q, "q");

    return zbddCache != null ? __union_cache(p, q) : __union(p, q);
  }


  /**
   * @since 0.1.3
   */
  @Contract(mutates = "this")
  protected final int __union_cache(int p, int q)
  {
    if (q == ZBDD_EMPTY || p == q)
      return p;
    if (p == ZBDD_EMPTY)
      return q;

    int p_var = getVar(p);
    int q_var = getVar(q);

    if (p_var > q_var)
    {
      // swap p <-> q, p_var <-> q_var
      int tmp = p; p = q; q = tmp;
      tmp = p_var; p_var = q_var; q_var = tmp;
    }

    int r = zbddCache.getResult(UNION, p, q);
    if (r == MIN_VALUE)
    {
      __incRef(p);
      __incRef(q);

      if (p_var < q_var)
      {
        final int p0 = __union_cache(p, getP0(q));

        r = getNode(q_var, p0, getP1(q));
      }
      else
      {
        // p_var = q_var
        final int p0 = __incRef(__union_cache(getP0(p), getP0(q)));
        final int p1 = __union_cache(getP1(p), getP1(q));

        r = getNode(p_var, __decRef(p0), p1);
      }

      zbddCache.putResult(UNION, p, q, r);

      __decRef(q);
      __decRef(p);
    }

    return r;
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int intersect(@Range(from = 0, to = MAX_NODES) int p, @Range(from = 0, to = MAX_NODES) int q)
  {
    checkZbdd(p, "p");
    checkZbdd(q, "q");

    return zbddCache != null ? __intersect_cache(p, q) : __intersect(p, q);
  }


  /**
   * @since 0.1.3
   */
  @Contract(mutates = "this")
  protected final int __intersect_cache(int p, int q)
  {
    if (p == ZBDD_EMPTY || q == ZBDD_EMPTY)
      return ZBDD_EMPTY;
    if (p == q)
      return p;

    int r = zbddCache.getResult(INTERSECT, p, q);
    if (r == MIN_VALUE)
    {
      final int p_var = getVar(p);
      final int q_var = getVar(q);

      __incRef(p);
      __incRef(q);

      if (p_var > q_var)
        r = __intersect_cache(getP0(p), q);
      else if (p_var < q_var)
        r = __intersect_cache(p, getP0(q));
      else
      {
        final int p0 = __incRef(__intersect_cache(getP0(p), getP0(q)));
        final int p1 = __intersect_cache(getP1(p), getP1(q));

        r = getNode(p_var, __decRef(p0), p1);
      }

      zbddCache.putResult(INTERSECT, p, q, r);

      __decRef(q);
      __decRef(p);
    }

    return r;
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int difference(@Range(from = 0, to = MAX_NODES) int p, @Range(from = 0, to = MAX_NODES) int q)
  {
    checkZbdd(p, "p");
    checkZbdd(q, "q");

    return zbddCache != null ? __difference_cache(p, q) : __difference(p, q);
  }


  /**
   * @since 0.1.3
   */
  @Contract(mutates = "this")
  protected final int __difference_cache(int p, int q)
  {
    if (p == ZBDD_EMPTY || p == q)
      return ZBDD_EMPTY;
    if (q == ZBDD_EMPTY)
      return p;

    int r = zbddCache.getResult(DIFFERENCE, p, q);
    if (r == MIN_VALUE)
    {
      final int p_var = getVar(p);
      final int q_var = getVar(q);

      __incRef(p);
      __incRef(q);

      if (p_var < q_var)
        r = __difference_cache(p, getP0(q));
      else if (p_var > q_var)
      {
        final int p0 = __difference_cache(getP0(p), getP0(q));

        r = getNode(p_var, p0, getP1(p));
      }
      else
      {
        final int p0 = __incRef(__difference_cache(getP0(p), getP0(q)));
        final int p1 = __difference_cache(getP1(p), getP1(q));

        r = getNode(p_var, __decRef(p0), p1);
      }

      zbddCache.putResult(DIFFERENCE, p, q, r);

      __decRef(q);
      __decRef(p);
    }

    return r;
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int multiply(@Range(from = 0, to = MAX_NODES) int p, @Range(from = 0, to = MAX_NODES) int q)
  {
    checkZbdd(p, "p");
    checkZbdd(q, "q");

    return zbddCache != null ? __multiply_cache(p, q) : __multiply(p, q);
  }


  /**
   * @since 0.1.3
   */
  @Contract(mutates = "this")
  protected final int __multiply_cache(int p, int q)
  {
    if (p == ZBDD_EMPTY || q == ZBDD_EMPTY)
      return ZBDD_EMPTY;
    if (p == ZBDD_BASE)
      return q;
    if (q == ZBDD_BASE)
      return p;

    int p_var = getVar(p);
    int q_var = getVar(q);

    if (p_var > q_var)
    {
      // swap p <-> q, p_var <-> q_var
      int tmp = p; p = q; q = tmp;
      tmp = p_var; p_var = q_var; q_var = tmp;
    }

    int r = zbddCache.getResult(MULTIPLY, p, q);
    if (r == MIN_VALUE)
    {
      __incRef(p);
      __incRef(q);

      // factor P = p0 + v * p1
      final int p0 = __incRef(__subset0_cache(p, p_var));
      final int p1 = __incRef(__subset1_cache(p, p_var));

      // factor Q = q0 + v * q1
      final int q0 = __incRef(__subset0_cache(q, p_var));
      final int q1 = __incRef(__subset1_cache(q, p_var));

      // r = (p0 + v * p1) * (q0 + v * q1) = p0q0 + v * (p0q1 + p1q0 + p1q1)
      final int p0q0 = __incRef(__multiply_cache(p0, q0));
      final int p0q1 = __incRef(__multiply_cache(p0, q1));
      final int p1q0 = __incRef(__multiply_cache(p1, q0));
      final int p1q1 = __incRef(__multiply_cache(p1, q1));

      zbddCache.putResult(MULTIPLY, p, q, r = __union_cache(p0q0,
          __change_cache(__union_cache(__union_cache(p0q1, p1q0), p1q1), p_var)));

      __decRef(p1q1);
      __decRef(p1q0);
      __decRef(p0q1);
      __decRef(p0q0);
      __decRef(q1);
      __decRef(q0);
      __decRef(p1);
      __decRef(p0);
      __decRef(q);
      __decRef(p);
    }

    return r;
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int divide(@Range(from = 0, to = MAX_NODES) int p, @Range(from = 0, to = MAX_NODES) int q)
  {
    checkZbdd(p, "p");
    checkZbdd(q, "q");

    return zbddCache != null ? __divide_cache(p, q) : __divide(p, q);
  }


  /**
   * @since 0.1.3
   */
  @Contract(mutates = "this")
  protected final int __divide_cache(int p, int q)
  {
    if (p < 2)
      return ZBDD_EMPTY;
    if (p == q)
      return ZBDD_BASE;
    if (q == ZBDD_BASE)
      return p;

    int r = zbddCache.getResult(DIVIDE, p, q);
    if (r == MIN_VALUE)
    {
      __incRef(p);
      __incRef(q);

      final int v = getVar(q);

      // factor P = p0 + v * p1
      final int p0 = __incRef(__subset0_cache(p, v));
      final int p1 = __incRef(__subset1_cache(p, v));

      // factor Q = q0 + v * q1
      final int q0 = __incRef(__subset0_cache(q, v));
      final int q1 = __subset1_cache(q, v);

      final int r1 = __divide_cache(__decRef(p1), q1);

      if (r1 != ZBDD_EMPTY && q0 != ZBDD_EMPTY)
      {
        __incRef(r1);

        final int r0 = __divide_cache(p0, q0);

        r = __intersect_cache(__decRef(r1), r0);
      }
      else
        r = r1;

      zbddCache.putResult(DIVIDE, p, q, r);

      __decRef(q0);
      __decRef(p0);
      __decRef(q);
      __decRef(p);
    }

    return r;
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int modulo(@Range(from = 0, to = MAX_NODES) int p, @Range(from = 0, to = MAX_NODES) int q)
  {
    checkZbdd(p, "p");
    checkZbdd(q, "q");

    return zbddCache != null ? __modulo_cache(p, q) : __modulo(p, q);
  }


  /**
   * @since 0.1.3
   */
  @Contract(mutates = "this")
  protected final int __modulo_cache(int p, int q)
  {
    int r = zbddCache.getResult(MODULO, p, q);
    if (r == MIN_VALUE)
    {
      __incRef(p);
      __incRef(q);

      r = __difference_cache(p, __multiply_cache(q, __divide_cache(p, q)));

      zbddCache.putResult(MODULO, p, q, r);

      __decRef(q);
      __decRef(p);
    }

    return r;
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int atomize(@Range(from = 0, to = MAX_NODES) int zbdd)
  {
    checkZbdd(zbdd, "zbdd");

    return zbddCache != null ? __atomize_cache(zbdd) : __atomize(zbdd);
  }


  /**
   * @since 0.1.3
   */
  @Contract(mutates = "this")
  protected final int __atomize_cache(int zbdd)
  {
    if (zbdd < 2)
      return ZBDD_EMPTY;

    int r = zbddCache.getResult(ATOMIZE, zbdd);
    if (r == MIN_VALUE)
    {
      final int p0_atomized = __incRef(__atomize(getP0(__incRef(zbdd))));  // lock zbdd, p0_atomized
      final int p1_atomized = __atomize(getP1(zbdd));

      final int p0 = __atomize_union(__decRef(p0_atomized), p1_atomized);  // release p0_atomized

      zbddCache.putResult(ATOMIZE, zbdd, r = getNode(getVar(zbdd), p0, ZBDD_BASE));

      __decRef(zbdd);  // release zbdd
    }

    return r;
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int removeBase(@Range(from = 0, to = MAX_NODES) int zbdd)
  {
    checkZbdd(zbdd, "zbdd");

    return zbddCache != null ? __removeBase_cache(zbdd) : __removeBase(zbdd);
  }


  /**
   * @since 0.1.3
   */
  @Contract(mutates = "this")
  protected final int __removeBase_cache(int zbdd)
  {
    if (zbdd < 2)
      return ZBDD_EMPTY;

    int r = zbddCache.getResult(REMOVE_BASE, zbdd);
    if (r == MIN_VALUE)
    {
      __incRef(zbdd);

      final int p0 = __removeBase_cache(getP0(zbdd));
      r = getNode(getVar(zbdd), p0, getP1(zbdd));

      zbddCache.putResult(REMOVE_BASE, zbdd, r);

      __decRef(zbdd);
    }

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
