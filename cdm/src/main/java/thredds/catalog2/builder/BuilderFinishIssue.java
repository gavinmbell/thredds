package thredds.catalog2.builder;

/**
 * _more_
 *
 * @author edavis
 * @since 4.0
 */
public class BuilderFinishIssue
{
  private final String message;
  private final ThreddsBuilder builder;

  public BuilderFinishIssue( String message, ThreddsBuilder builder )
  {
    this.message = message;
    this.builder = builder;
  }

  public String getMessage()
  {
    return this.message;
  }

  public ThreddsBuilder getBuilder()
  {
    return this.builder;
  }
}