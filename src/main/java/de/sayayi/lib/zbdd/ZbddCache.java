package de.sayayi.lib.zbdd;

import org.jetbrains.annotations.NotNull;

import java.util.function.IntSupplier;


public interface ZbddCache
{
  int lookupOrPutIfAbsent(@NotNull UnaryOperation operation, int zbdd, int var, @NotNull IntSupplier resultSupplier);


  int lookupOrPutIfAbsent(@NotNull BinaryOperation operation, int p, int q, @NotNull IntSupplier resultSupplier);


  void clear();




  enum UnaryOperation {
    SUBSET0, SUBSET1, CHANGE
  }




  enum BinaryOperation {
    UNION, INTERSECT, DIFF, MUL, DIV, MOD
  }



  enum NoCache implements ZbddCache
  {
    INSTANCE;


    @Override
    public int lookupOrPutIfAbsent(@NotNull UnaryOperation operation, int zbdd, int var,
                                   @NotNull IntSupplier resultSupplier) {
      return resultSupplier.getAsInt();
    }


    @Override
    public int lookupOrPutIfAbsent(@NotNull BinaryOperation operation, int p, int q,
                                   @NotNull IntSupplier resultSupplier) {
      return resultSupplier.getAsInt();
    }


    @Override
    public void clear() {
    }
  }
}
