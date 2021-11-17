package de.sayayi.lib.zbdd;


public class ZbddException extends RuntimeException
{
  public ZbddException(String message) {
    super(message);
  }


  public ZbddException(String message, Throwable cause) {
    super(message, cause);
  }
}
