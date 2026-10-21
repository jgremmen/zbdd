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
package de.sayayi.lib.zbdd.internal;

import de.sayayi.lib.zbdd.Zbdd.WithCache;
import de.sayayi.lib.zbdd.ZbddCapacityAdvisor;
import de.sayayi.lib.zbdd.cache.ZbddCache;
import de.sayayi.lib.zbdd.exception.ZbddException;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import static de.sayayi.lib.zbdd.cache.ZbddCache.Operation1.*;
import static de.sayayi.lib.zbdd.cache.ZbddCache.Operation2.*;
import static java.lang.Integer.MIN_VALUE;
import static java.util.Objects.requireNonNull;


/**
 * Extension of {@link ZbddImpl} that adds operation result caching via a {@link ZbddCache}. Each ZBDD operation
 * first checks the cache for a previously computed result before delegating to the base implementation.
 * <p>
 * The cache is automatically cleared before garbage collection and when the ZBDD is cleared, ensuring consistency
 * between cached results and the current state of the diagram.
 * <p>
 * Instances are created through the {@link de.sayayi.lib.zbdd.ZbddFactory ZbddFactory}.
 *
 * @author Jeroen Gremmen
 * @since 0.5.0
 */
@ApiStatus.Internal
@SuppressWarnings("DuplicatedCode")
public final class ZbddCachedImpl extends ZbddImpl implements WithCache
{
  /**
   * Zbdd caches currently in use, mapped to the instance using them. Cached results are keyed by node numbers, which
   * are only meaningful for a single zbdd instance, so a cache must never be used by more than one instance at a time.
   * <p>
   * Both the cache and its owner are referenced weakly, so a cache becomes available again as soon as its owner is no
   * longer reachable. Access must be synchronized on the map itself.
   */
  private static final Map<ZbddCache,WeakReference<ZbddCachedImpl>> CACHE_OWNERS = new WeakHashMap<>();

  // task kind of the iterative binary operations: p, q: store the result on top of the results stack for p and q
  private static final int TASK_STORE = 5;

  private ZbddCache zbddCache;


  /**
   * Creates a new cached ZBDD instance with the given capacity advisor and cache.
   *
   * @param capacityAdvisor  capacity advisor, not {@code null}
   * @param zbddCache        ZBDD operation cache to use for memoization, not {@code null}
   */
  @Contract(mutates = "param2")
  public ZbddCachedImpl(@NotNull ZbddCapacityAdvisor capacityAdvisor, @NotNull ZbddCache zbddCache)
  {
    super(capacityAdvisor);

    setZbddCache(zbddCache);

    registerCallback(new ZbddCallback() {
      @Override public void beforeClear() { ZbddCachedImpl.this.zbddCache.clear(); }
      @Override public void beforeGc() { ZbddCachedImpl.this.zbddCache.clear(); }
    });
  }


  /** {@inheritDoc} */
  @Override
  @Contract(pure = true)
  public @NotNull ZbddCache getZbddCache() {
    return zbddCache;
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this,param1")
  public void setZbddCache(@NotNull ZbddCache zbddCache)
  {
    requireNonNull(zbddCache);

    synchronized(CACHE_OWNERS) {
      final var ownerRef = CACHE_OWNERS.get(zbddCache);
      final var owner = ownerRef == null ? null : ownerRef.get();

      if (owner != null && owner != this)
        throw new ZbddException("zbdd cache is already in use by another zbdd instance");

      // release the previous cache, making it available for other zbdd instances
      if (this.zbddCache != null && this.zbddCache != zbddCache)
        CACHE_OWNERS.remove(this.zbddCache);

      if (owner == null)
        CACHE_OWNERS.put(zbddCache, new WeakReference<>(this));
    }

    zbddCache.clear();

    this.zbddCache = zbddCache;
  }


  /**
   * {@inheritDoc}
   * <p>
   * This implementation caches results using the {@link ZbddCache}.
   *
   * @since 0.1.3
   */
  @Override
  @Contract(mutates = "this")
  protected int __subset0(int zbdd, int var)
  {
    var top = __getVar(zbdd);

    if (top < var)
      return zbdd;

    if (top == var)
      return __getP0(zbdd);

    final var mark = saveAndProtect(zbdd);
    final var tasks = new IntStack(24);
    final var results = new IntStack(24);

    tasks.push(zbdd);
    tasks.push(TASK_COMPUTE);

    do {
      final var task = tasks.pop();
      final var node = tasks.pop();
      int r;

      if (task == TASK_COMBINE)
      {
        final var p1 = results.pop();
        final var p0 = results.pop();

        r = __getNode(__getVar(node), p0, p1);

        // release the protection of the branch results (leaf nodes are not protected)
        protectedNodes.truncate(protectedNodes.size() - (p0 >= 2 ? 1 : 0) - (p1 >= 2 ? 1 : 0));
        zbddCache.putResult(SUBSET0, node, var, r);
      }
      else if ((top = __getVar(node)) < var)
        r = node;
      else if (top == var)
        r = __getP0(node);
      else if ((r = zbddCache.getResult(SUBSET0, node, var)) == MIN_VALUE)
      {
        tasks.push(node);
        tasks.push(TASK_COMBINE);
        tasks.push(__getP1(node));
        tasks.push(TASK_COMPUTE);
        tasks.push(__getP0(node));
        tasks.push(TASK_COMPUTE);
        continue;
      }

      results.push(protect(r));
    } while(tasks.isNotEmpty());

    protectedNodes.truncate(mark);

    return results.pop();
  }


  /**
   * {@inheritDoc}
   * <p>
   * This implementation caches results using the {@link ZbddCache}.
   *
   * @since 0.1.3
   */
  @Override
  @Contract(mutates = "this")
  protected int __subset1(int zbdd, int var)
  {
    var top = __getVar(zbdd);

    if (top < var)
      return EMPTY;

    if (top == var)
      return __getP1(zbdd);

    final var mark = saveAndProtect(zbdd);
    final var tasks = new IntStack(24);
    final var results = new IntStack(24);

    tasks.push(zbdd);
    tasks.push(TASK_COMPUTE);

    do {
      final var task = tasks.pop();
      final var node = tasks.pop();
      int r;

      if (task == TASK_COMBINE)
      {
        final var p1 = results.pop();
        final var p0 = results.pop();

        r = __getNode(__getVar(node), p0, p1);

        // release the protection of the branch results (leaf nodes are not protected)
        protectedNodes.truncate(protectedNodes.size() - (p0 >= 2 ? 1 : 0) - (p1 >= 2 ? 1 : 0));
        zbddCache.putResult(SUBSET1, node, var, r);
      }
      else if ((top = __getVar(node)) < var)
        r = EMPTY;
      else if (top == var)
        r = __getP1(node);
      else if ((r = zbddCache.getResult(SUBSET1, node, var)) == MIN_VALUE)
      {
        tasks.push(node);
        tasks.push(TASK_COMBINE);
        tasks.push(__getP1(node));
        tasks.push(TASK_COMPUTE);
        tasks.push(__getP0(node));
        tasks.push(TASK_COMPUTE);
        continue;
      }

      results.push(protect(r));
    } while(tasks.isNotEmpty());

    protectedNodes.truncate(mark);

    return results.pop();
  }


  /**
   * {@inheritDoc}
   * <p>
   * This implementation caches results using the {@link ZbddCache}.
   *
   * @since 0.1.3
   */
  @Override
  @Contract(mutates = "this")
  protected int __change(int zbdd, int var)
  {
    var top = __getVar(zbdd);

    if (top < var)
      return __getNode(var, EMPTY, zbdd);

    final var mark = saveAndProtect(zbdd);
    final var tasks = new IntStack(24);
    final var results = new IntStack(24);

    tasks.push(zbdd);
    tasks.push(TASK_COMPUTE);

    do {
      final var task = tasks.pop();
      final var node = tasks.pop();
      int r;

      if (task == TASK_COMBINE)
      {
        final var p1 = results.pop();
        final var p0 = results.pop();

        r = __getNode(__getVar(node), p0, p1);

        // release the protection of the branch results (leaf nodes are not protected)
        protectedNodes.truncate(protectedNodes.size() - (p0 >= 2 ? 1 : 0) - (p1 >= 2 ? 1 : 0));
        zbddCache.putResult(CHANGE, node, var, r);
      }
      else if ((top = __getVar(node)) < var)
        r = __getNode(var, EMPTY, node);
      else if ((r = zbddCache.getResult(CHANGE, node, var)) == MIN_VALUE)
      {
        if (top > var)
        {
          tasks.push(node);
          tasks.push(TASK_COMBINE);
          tasks.push(__getP1(node));
          tasks.push(TASK_COMPUTE);
          tasks.push(__getP0(node));
          tasks.push(TASK_COMPUTE);
          continue;
        }

        zbddCache.putResult(CHANGE, node, var, r = __getNode(var, __getP1(node), __getP0(node)));
      }

      results.push(protect(r));
    } while(tasks.isNotEmpty());

    protectedNodes.truncate(mark);

    return results.pop();
  }


  /**
   * {@inheritDoc}
   * <p>
   * This implementation caches results using the {@link ZbddCache}.
   *
   * @since 0.1.3
   */
  @Override
  @Contract(mutates = "this")
  protected int __count(int zbdd)
  {
    if (zbdd < 2)
      return zbdd;

    var r = zbddCache.getResult(COUNT, zbdd);
    if (r == MIN_VALUE)
      zbddCache.putResult(COUNT, zbdd, r = super.__count(zbdd));

    return r;
  }


  /**
   * {@inheritDoc}
   * <p>
   * This implementation caches results using the {@link ZbddCache}.
   *
   * @since 0.1.3
   */
  @Override
  @Contract(mutates = "this")
  protected int __union(int p, int q)
  {
    if (q == EMPTY || p == q)
      return p;
    if (p == EMPTY)
      return q;

    final var mark = saveAndProtect(p, q);
    final var tasks = new IntStack(24);
    final var results = new IntStack(24);

    tasks.push(p);
    tasks.push(q);
    tasks.push(TASK_COMPUTE);

    do {
      final var task = tasks.pop();
      int r;

      if (task == TASK_COMBINE)
      {
        final var var = tasks.pop();
        final var p1 = results.pop();
        final var p0 = results.pop();

        r = __getNode(var, p0, p1);

        // release the protection of the branch results (leaf nodes are not protected)
        protectedNodes.truncate(protectedNodes.size() - (p0 >= 2 ? 1 : 0) - (p1 >= 2 ? 1 : 0));

        q = tasks.pop();
        p = tasks.pop();
        zbddCache.putResult(UNION, p, q, r);
      }
      else if (task == TASK_COMBINE_P1)
      {
        final var var = tasks.pop();
        final var p1 = tasks.pop();
        final var p0 = results.pop();

        r = __getNode(var, p0, p1);

        // release the protection of the branch result (leaf nodes are not protected)
        protectedNodes.truncate(protectedNodes.size() - (p0 >= 2 ? 1 : 0));

        q = tasks.pop();
        p = tasks.pop();
        zbddCache.putResult(UNION, p, q, r);
      }
      else
      {
        q = tasks.pop();
        p = tasks.pop();

        if (q == EMPTY || p == q)
          r = p;
        else if (p == EMPTY)
          r = q;
        else
        {
          var p_var = __getVar(p);
          var q_var = __getVar(q);

          if (p > q)
          {
            // normalize the cache key: swap p <-> q, p_var <-> q_var
            var tmp = p; p = q; q = tmp;
            tmp = p_var; p_var = q_var; q_var = tmp;
          }

          if ((r = zbddCache.getResult(UNION, p, q)) == MIN_VALUE)
          {
            tasks.push(p);
            tasks.push(q);

            if (p_var > q_var)
            {
              // swap p <-> q, p_var <-> q_var
              var tmp = p; p = q; q = tmp;
              tmp = p_var; p_var = q_var; q_var = tmp;
            }

            if (p_var < q_var)
            {
              // union(p, q) = getNode(q_var, union(p, q0), q1)
              tasks.push(__getP1(q));
              tasks.push(q_var);
              tasks.push(TASK_COMBINE_P1);
              tasks.push(p);
              tasks.push(__getP0(q));
            }
            else
            {
              tasks.push(p_var);
              tasks.push(TASK_COMBINE);
              tasks.push(__getP1(p));
              tasks.push(__getP1(q));
              tasks.push(TASK_COMPUTE);
              tasks.push(__getP0(p));
              tasks.push(__getP0(q));
            }

            tasks.push(TASK_COMPUTE);
            continue;
          }
        }
      }

      results.push(protect(r));
    } while(tasks.isNotEmpty());

    protectedNodes.truncate(mark);

    return results.pop();
  }


  /**
   * {@inheritDoc}
   * <p>
   * This implementation caches results using the {@link ZbddCache}.
   *
   * @since 0.1.3
   */
  @Override
  @Contract(mutates = "this")
  protected int __intersect(int p, int q)
  {
    if (p == EMPTY || q == EMPTY)
      return EMPTY;
    if (p == q)
      return p;

    final var mark = saveAndProtect(p, q);
    final var tasks = new IntStack(24);
    final var results = new IntStack(24);

    tasks.push(p);
    tasks.push(q);
    tasks.push(TASK_COMPUTE);

    do {
      final var task = tasks.pop();
      int r;

      if (task == TASK_COMBINE)
      {
        final var var = tasks.pop();
        final var p1 = results.pop();
        final var p0 = results.pop();

        r = __getNode(var, p0, p1);

        // release the protection of the branch results (leaf nodes are not protected)
        protectedNodes.truncate(protectedNodes.size() - (p0 >= 2 ? 1 : 0) - (p1 >= 2 ? 1 : 0));

        q = tasks.pop();
        p = tasks.pop();
        zbddCache.putResult(INTERSECT, p, q, r);
      }
      else if (task == TASK_STORE)
      {
        q = tasks.pop();
        p = tasks.pop();
        zbddCache.putResult(INTERSECT, p, q, r = results.pop());
        results.push(r);
        continue;
      }
      else
      {
        q = tasks.pop();
        p = tasks.pop();

        if (p > q)
        {
          // normalize the cache key: swap p <-> q
          final var tmp = p; p = q; q = tmp;
        }

        if (p == EMPTY)
          r = EMPTY;
        else if (p == q)
          r = p;
        else if ((r = zbddCache.getResult(INTERSECT, p, q)) == MIN_VALUE)
        {
          final var p_var = __getVar(p);
          final var q_var = __getVar(q);

          if (p_var > q_var)
          {
            tasks.push(p);
            tasks.push(q);
            tasks.push(TASK_STORE);
            tasks.push(__getP0(p));
            tasks.push(q);
          }
          else if (p_var < q_var)
          {
            tasks.push(p);
            tasks.push(q);
            tasks.push(TASK_STORE);
            tasks.push(p);
            tasks.push(__getP0(q));
          }
          else
          {
            tasks.push(p);
            tasks.push(q);
            tasks.push(p_var);
            tasks.push(TASK_COMBINE);
            tasks.push(__getP1(p));
            tasks.push(__getP1(q));
            tasks.push(TASK_COMPUTE);
            tasks.push(__getP0(p));
            tasks.push(__getP0(q));
          }

          tasks.push(TASK_COMPUTE);
          continue;
        }
      }

      results.push(protect(r));
    } while(tasks.isNotEmpty());

    protectedNodes.truncate(mark);

    return results.pop();
  }


  /**
   * {@inheritDoc}
   * <p>
   * This implementation caches results using the {@link ZbddCache}.
   *
   * @since 0.1.3
   */
  @Override
  @Contract(mutates = "this")
  protected int __difference(int p, int q)
  {
    if (p == EMPTY || p == q)
      return EMPTY;
    if (q == EMPTY)
      return p;

    final var mark = saveAndProtect(p, q);
    final var tasks = new IntStack(24);
    final var results = new IntStack(24);

    tasks.push(p);
    tasks.push(q);
    tasks.push(TASK_COMPUTE);

    do {
      final var task = tasks.pop();
      int r;

      if (task == TASK_COMBINE)
      {
        final var var = tasks.pop();
        final var p1 = results.pop();
        final var p0 = results.pop();

        r = __getNode(var, p0, p1);

        // release the protection of the branch results (leaf nodes are not protected)
        protectedNodes.truncate(protectedNodes.size() - (p0 >= 2 ? 1 : 0) - (p1 >= 2 ? 1 : 0));

        q = tasks.pop();
        p = tasks.pop();
        zbddCache.putResult(DIFFERENCE, p, q, r);
      }
      else if (task == TASK_COMBINE_P1)
      {
        final var var = tasks.pop();
        final var p1 = tasks.pop();
        final var p0 = results.pop();

        r = __getNode(var, p0, p1);

        // release the protection of the branch result (leaf nodes are not protected)
        protectedNodes.truncate(protectedNodes.size() - (p0 >= 2 ? 1 : 0));

        q = tasks.pop();
        p = tasks.pop();
        zbddCache.putResult(DIFFERENCE, p, q, r);
      }
      else if (task == TASK_STORE)
      {
        q = tasks.pop();
        p = tasks.pop();
        zbddCache.putResult(DIFFERENCE, p, q, r = results.pop());
        results.push(r);
        continue;
      }
      else
      {
        q = tasks.pop();
        p = tasks.pop();

        if (p == EMPTY || p == q)
          r = EMPTY;
        else if (q == EMPTY)
          r = p;
        else if ((r = zbddCache.getResult(DIFFERENCE, p, q)) == MIN_VALUE)
        {
          if (q == BASE)
            zbddCache.putResult(DIFFERENCE, p, q, r = __removeBase(p));
          else
          {
            final var p_var = __getVar(p);
            final var q_var = __getVar(q);

            if (p_var < q_var)
            {
              tasks.push(p);
              tasks.push(q);
              tasks.push(TASK_STORE);
              tasks.push(p);
              tasks.push(__getP0(q));
            }
            else if (p_var > q_var)
            {
              // difference(p, q) = getNode(p_var, difference(p0, q), p1)
              tasks.push(p);
              tasks.push(q);
              tasks.push(__getP1(p));
              tasks.push(p_var);
              tasks.push(TASK_COMBINE_P1);
              tasks.push(__getP0(p));
              tasks.push(q);
            }
            else
            {
              tasks.push(p);
              tasks.push(q);
              tasks.push(p_var);
              tasks.push(TASK_COMBINE);
              tasks.push(__getP1(p));
              tasks.push(__getP1(q));
              tasks.push(TASK_COMPUTE);
              tasks.push(__getP0(p));
              tasks.push(__getP0(q));
            }

            tasks.push(TASK_COMPUTE);
            continue;
          }
        }
      }

      results.push(protect(r));
    } while(tasks.isNotEmpty());

    protectedNodes.truncate(mark);

    return results.pop();
  }


  /**
   * {@inheritDoc}
   * <p>
   * This implementation caches results using the {@link ZbddCache}.
   *
   * @since 0.1.3
   */
  @Override
  @Contract(mutates = "this")
  protected int __multiply(int p, int q)
  {
    if (p == EMPTY || q == EMPTY)
      return EMPTY;
    if (p == BASE)
      return q;
    if (q == BASE)
      return p;

    final var mark = saveAndProtect(p, q);
    final var tasks = new IntStack(24);
    final var results = new IntStack(24);

    tasks.push(p);
    tasks.push(q);
    tasks.push(TASK_COMPUTE);

    do {
      final var task = tasks.pop();
      int r;

      if (task == TASK_COMBINE)
      {
        final var var = tasks.pop();
        final var resultsMark = tasks.pop();
        final var pq1 = results.pop();
        final var pq0 = results.pop();

        r = __getNode(var, pq0, pq1);

        // release the protection of the products
        protectedNodes.truncate(resultsMark);

        q = tasks.pop();
        p = tasks.pop();
        zbddCache.putResult(MULTIPLY, p, q, r);
      }
      else if (task == TASK_COMBINE_PRODUCTS)
      {
        final var var = tasks.pop();
        final var resultsMark = tasks.pop();
        final var p1q1 = results.pop();
        final var p1q0 = results.pop();
        final var p0q1 = results.pop();
        final var p0q0 = results.pop();

        // r = (p0 + v * p1) * (q0 + v * q1) = p0q0 + v * (p0q1 + p1q0 + p1q1)
        r = __getNode(var, p0q0, __union(__union(p0q1, p1q0), p1q1));

        // release the protection of the products
        protectedNodes.truncate(resultsMark);

        q = tasks.pop();
        p = tasks.pop();
        zbddCache.putResult(MULTIPLY, p, q, r);
      }
      else
      {
        q = tasks.pop();
        p = tasks.pop();

        if (p == EMPTY || q == EMPTY)
          r = EMPTY;
        else if (p == BASE)
          r = q;
        else if (q == BASE)
          r = p;
        else
        {
          var p_var = __getVar(p);
          var q_var = __getVar(q);

          if (p > q)
          {
            // normalize the cache key: swap p <-> q, p_var <-> q_var
            var tmp = p; p = q; q = tmp;
            tmp = p_var; p_var = q_var; q_var = tmp;
          }

          if ((r = zbddCache.getResult(MULTIPLY, p, q)) == MIN_VALUE)
          {
            tasks.push(p);
            tasks.push(q);

            if (p_var > q_var)
            {
              // swap p <-> q, p_var <-> q_var
              var tmp = p; p = q; q = tmp;
              tmp = p_var; p_var = q_var; q_var = tmp;
            }

            tasks.push(protectedNodes.size());
            tasks.push(q_var);

            if (p_var < q_var)
            {
              // v = q_var does not occur in p: r = p * (q0 + v * q1) = p * q0 + v * (p * q1)
              tasks.push(TASK_COMBINE);
              tasks.push(p);
              tasks.push(__getP1(q));
              tasks.push(TASK_COMPUTE);
              tasks.push(p);
              tasks.push(__getP0(q));
              tasks.push(TASK_COMPUTE);
            }
            else
            {
              // compute the products p0q0, p0q1, p1q0 and p1q1 in this order
              final var p0 = __getP0(p);
              final var p1 = __getP1(p);
              final var q0 = __getP0(q);
              final var q1 = __getP1(q);

              tasks.push(TASK_COMBINE_PRODUCTS);
              tasks.push(p1);
              tasks.push(q1);
              tasks.push(TASK_COMPUTE);
              tasks.push(p1);
              tasks.push(q0);
              tasks.push(TASK_COMPUTE);
              tasks.push(p0);
              tasks.push(q1);
              tasks.push(TASK_COMPUTE);
              tasks.push(p0);
              tasks.push(q0);
              tasks.push(TASK_COMPUTE);
            }

            continue;
          }
        }
      }

      results.push(protect(r));
    } while(tasks.isNotEmpty());

    protectedNodes.truncate(mark);

    return results.pop();
  }


  /**
   * {@inheritDoc}
   * <p>
   * This implementation caches results using the {@link ZbddCache}.
   *
   * @since 0.1.3
   */
  @Override
  @Contract(mutates = "this")
  protected int __divide(int p, int q)
  {
    if (q == BASE)
      return p;  // p / { ø } = p
    else if (p == EMPTY || p == BASE)
      return EMPTY;  // {} / q = {} or { ø } / q = {} for q != { ø }
    else if (p == q)
      return BASE;  // p / p = { ø }

    final var mark = saveAndProtect(p, q);
    final var tasks = new IntStack(24);
    final var results = new IntStack(24);

    tasks.push(p);
    tasks.push(q);
    tasks.push(TASK_COMPUTE);

    do {
      final var task = tasks.pop();
      int r;

      if (task == TASK_CONTINUE)
      {
        final var q0 = tasks.pop();
        final var p0 = tasks.pop();

        if ((r = results.pop()) != EMPTY && q0 != EMPTY)
        {
          // r = r1 ∩ (p0 / q0); r1 remains protected on top of the results stack
          results.push(r);
          tasks.push(TASK_COMBINE);
          tasks.push(p0);
          tasks.push(q0);
          tasks.push(TASK_COMPUTE);
          continue;
        }

        // r = r1; release the protection of the factored operands and r1
        protectedNodes.truncate(tasks.pop());

        q = tasks.pop();
        p = tasks.pop();
        zbddCache.putResult(DIVIDE, p, q, r);
      }
      else if (task == TASK_COMBINE)
      {
        final var r0 = results.pop();
        final var r1 = results.pop();

        r = __intersect(r1, r0);

        // release the protection of the factored operands, r1 and r0
        protectedNodes.truncate(tasks.pop());

        q = tasks.pop();
        p = tasks.pop();
        zbddCache.putResult(DIVIDE, p, q, r);
      }
      else
      {
        q = tasks.pop();
        p = tasks.pop();

        if (q == BASE)
          r = p;  // p / { ø } = p
        else if (p == EMPTY || p == BASE)
          r = EMPTY;  // {} / q = {} or { ø } / q = {} for q != { ø }
        else if (p == q)
          r = BASE;  // p / p = { ø }
        else if ((r = zbddCache.getResult(DIVIDE, p, q)) == MIN_VALUE)
        {
          final var v = __getVar(q);

          tasks.push(p);
          tasks.push(q);
          tasks.push(protectedNodes.size());

          // factor P = p0 + v * p1
          final var p0 = protect(__subset0(p, v));
          final var p1 = protect(__subset1(p, v));

          // factor Q = q0 + v * q1
          final var q0 = protect(__subset0(q, v));
          final var q1 = protect(__subset1(q, v));

          // r1 = p1 / q1 is computed first; the continuation decides whether p0 / q0 is required
          tasks.push(p0);
          tasks.push(q0);
          tasks.push(TASK_CONTINUE);
          tasks.push(p1);
          tasks.push(q1);
          tasks.push(TASK_COMPUTE);
          continue;
        }
      }

      results.push(protect(r));
    } while(tasks.isNotEmpty());

    protectedNodes.truncate(mark);

    return results.pop();
  }


  /**
   * {@inheritDoc}
   * <p>
   * This implementation caches results using the {@link ZbddCache}.
   *
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
   * {@inheritDoc}
   * <p>
   * This implementation caches results using the {@link ZbddCache}.
   *
   * @since 0.1.3
   */
  @Override
  @Contract(mutates = "this")
  protected int __atomize(int zbdd)
  {
    if (zbdd < 2)
      return EMPTY;

    int r = zbddCache.getResult(ATOMIZE, zbdd);
    if (r != MIN_VALUE)
      return r;

    final var mark = saveAndProtect(zbdd);
    final var tasks = new IntStack(24);
    final var results = new IntStack(24);

    // the cache has been checked for zbdd already, so start with its branches
    tasks.push(zbdd);
    tasks.push(TASK_COMBINE);
    tasks.push(__getP1(zbdd));
    tasks.push(TASK_COMPUTE);
    tasks.push(__getP0(zbdd));
    tasks.push(TASK_COMPUTE);

    do {
      final var task = tasks.pop();
      final var node = tasks.pop();

      if (task == TASK_COMBINE)
      {
        final var p1 = results.pop();
        final var p0 = results.pop();

        r = __getNode(__getVar(node), __atomize_union(p0, p1), BASE);

        // release the protection of the branch results (leaf nodes are not protected)
        protectedNodes.truncate(protectedNodes.size() - (p0 >= 2 ? 1 : 0) - (p1 >= 2 ? 1 : 0));
        zbddCache.putResult(ATOMIZE, node, r);
      }
      else if (node < 2)
        r = EMPTY;
      else if ((r = zbddCache.getResult(ATOMIZE, node)) == MIN_VALUE)
      {
        tasks.push(node);
        tasks.push(TASK_COMBINE);
        tasks.push(__getP1(node));
        tasks.push(TASK_COMPUTE);
        tasks.push(__getP0(node));
        tasks.push(TASK_COMPUTE);
        continue;
      }

      results.push(protect(r));
    } while(tasks.isNotEmpty());

    protectedNodes.truncate(mark);

    return results.pop();
  }


  /**
   * {@inheritDoc}
   * <p>
   * This implementation caches results using the {@link ZbddCache}.
   *
   * @since 0.1.3
   */
  @Override
  @Contract(mutates = "this")
  protected int __removeBase(int zbdd)
  {
    if (zbdd < 2)
      return EMPTY;

    int r = zbddCache.getResult(REMOVE_BASE, zbdd);
    if (r != MIN_VALUE)
      return r;

    final var mark = saveAndProtect(zbdd);
    final var stack = new IntStack(24);

    // descend the 0-branch chain until a terminal node or a cached result is reached
    do {
      stack.push(zbdd);
    } while((zbdd = __getP0(zbdd)) >= 2 && (r = zbddCache.getResult(REMOVE_BASE, zbdd)) == MIN_VALUE);

    if (zbdd < 2)
      r = EMPTY;

    // rebuild the 0-branch chain bottom-up
    do {
      final var node = stack.pop();
      zbddCache.putResult(REMOVE_BASE, node, r = __getNode(__getVar(node), r, __getP1(node)));
    } while(stack.isNotEmpty());

    protectedNodes.truncate(mark);

    return r;
  }
}
