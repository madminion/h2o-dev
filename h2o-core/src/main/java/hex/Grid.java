package hex;

import water.*;
import water.H2O.H2OCountedCompleter;
import water.fvec.Frame;
import water.nbhm.NonBlockingHashMap;
import water.rapids.ASTddply.Group;

/** A Grid of Models
 *  Used to explore Model hyper-parameter space.  Lazily filled in, this object
 *  represents the potentially infinite variety of hyperparameters of a given
 *  model & dataset.
 *
 *  One subclass per kind of Model, e.g. KMeans or GLM or GBM or DL.  The Grid
 *  tracks Models and their hyperparameters, and will allow discovery of
 *  existing Models by hyperparameter, or building Models on demand by
 *  hyperparameter.  The Grid can manage a (simplistic) hyperparameter search
 *  space.
 *
 *  Hyperparameter values are limited to doubles in the API, but can be
 *  anything the subclass Grid desires internally.  E.g. the Grid for KMeans
 *  will convert the initial center selection Enum to and from a simple integer
 *  value internally.
 */
public abstract class Grid<G extends Grid<G>> extends Lockable<G> {
  protected final Frame _fr;    // The training frame for this grid of models
  // A cache of double[] hyper-parameters mapping to Models
  final NonBlockingHashMap<Group,Model> _cache = new NonBlockingHashMap<>();

  protected Grid( Key key, Frame fr ) { super(key); _fr = fr; }

  /** @return Model name */
  protected abstract String modelName();

  /** @return Number of hyperparameters this Grid will Grid-over */
  protected abstract int nHyperParms();

  /** @param h The h-th hyperparameter
   *  @return h-th hyperparameter name; should correspond to a Model.Parameter field name */
  protected abstract String hyperName(int h);

  /** @param h The h-th hyperparameter
   *  @return Preferred string representation of h-th hyperparameter */
  protected abstract String hyperToString(int h, double val);

  /** @param h The h-th hyperparameter
   *  @param m A model to fetch the hyperparameter from
   *  @return The h-th hyperparameter value from Model m  */
  protected abstract double hyperValue( int h, Model m );
  
  /** @param h The h-th hyperparameter
   *  @return The h-th hyperparameter default value */
  protected abstract double hyperDefault( int h );

  /** Ask the Grid for a suggested next hyperparameter value, given an existing
   *  Model as a starting point and the complete set of hyperparameter limits.
   *  Returning a NaN signals there is no next suggestion, which is reasonable
   *  if the obvious "next" value does not exist (e.g. exhausted all
   *  possibilities of an enum).  It is OK if a Model for the suggested value
   *  already exists; this will be checked before building any model.
   *  @param h The h-th hyperparameter 
   *  @param m A model to act as a starting point 
   *  @param hyperLimits Upper bounds for this search 
   *  @return Suggested next value for hyperparameter h or NaN if no next value */
  protected abstract double suggestedNextHyperValue( int h, Model m, double[] hyperLimits );

  /** @return The data frame used to train all these models.  All models are
   *  trained on the same data frame, but might be validated on multiple
   *  different frames. */
  public Frame trainingFrame() { return _fr; }

  /** @return Factory method to return the grid for a particular modeling class
   *  and frame.  */
  protected static Key keyName( String modelName, Frame fr ) {
    if( fr._key==null ) throw new IllegalArgumentException("The frame being grid-searched over must have a Key");
    return Key.make("Grid_"+modelName+"_"+fr._key.toString());
  }

  /** @param hypers A set of hyper parameter values
   *  @return A model run with these parameters, or null if the model does not exist. */
  public Model model( double[] hypers ) { return _cache.get(new Group(hypers)); }

  /** @param hypers A set of hyper parameter values
   *  @return A model run with these parameters, typically built on demand and
   *  not cached - expected to be an expensive operation.  If the model in question
   *  is "in progress", a 2nd build will NOT be kicked off.  This is a blocking call. */
  public Model buildModel( double[] hypers ) {
    Model m = model(hypers);
    if( m != null ) return m;
    m = (Model)(startBuildModel(hypers).get());
    _cache.put(new Group(hypers.clone()),m);
    return m;
  }
  
  /** @param hypers A set of hyper parameter values
   *  @return A ModelBuilder, blindly filled with parameters.  Assumed to be
   *  cheap; used to check hyperparameter sanity */
  protected abstract ModelBuilder getBuilder( double[] hypers );

  /** @param hypers A set of hyper parameter values
   *  @return A Future of a model run with these parameters, typically built on
   *  demand and not cached - expected to be an expensive operation.  If the
   *  model in question is "in progress", a 2nd build will NOT be kicked off.
   *  This is a non-blocking call. */
  public ModelBuilder startBuildModel( double[] hypers ) {
    if( model(hypers) != null ) return null;
    ModelBuilder mb = getBuilder(hypers);
    mb.trainModel();
    return mb;
  }
  
  /** @param hyperSearch A set of arrays of hyper parameter values, used to
   *  specify a simple fully-filled-in grid search.
   *  @return GridSearch Job, with models run with these parameters, built as
   *  needed - expected to be an expensive operation.  If the models in
   *  question are "in progress", a 2nd build will NOT be kicked off.  This is
   *  a non-blocking call. */
  public GridSearch startGridSearch( final double[][] hyperSearch ) { return new GridSearch(_key,hyperSearch).start(); }

  // Cleanup models and grid
  @Override protected Futures remove_impl( Futures fs ) {
    for( Model m : _cache.values() )
      m.remove(fs);
    _cache.clear();
    return fs;
  }

  // A search over a hyperparameter space
  public class GridSearch extends Job<Grid> {
    double[][] _hyperSearch;
    final int _total_models;
    GridSearch( Key gkey, double[][] hyperSearch ) {
      super(Key.make("GridSearch_"+modelName()+Key.rand()), gkey, modelName()+" Grid Search");
      _hyperSearch = hyperSearch;
      // Replace null hyperparameters with the model default
      for( int i=0; i<_hyperSearch.length; i++ )
        if( _hyperSearch[i] == null )
          _hyperSearch[i] = new double[]{hyperDefault(i)};
      // Count of models in this search
      int work = 1;
      for( double hparms[] : _hyperSearch )
        work *= hparms.length;
      _total_models = work;

      // Check all parameter combos for validity
      double[] hypers = new double[nHyperParms()];
      for( int[] hidx = new int[nHyperParms()]; hidx != null; hidx = nextModel(hidx) ) {
        ModelBuilder mb = getBuilder(hypers(hidx,hypers));
        mb.init(false);
        if( mb.error_count() > 0 ) 
          throw new IllegalArgumentException(mb.validationErrors());
      }
    }

    GridSearch start() {
      start(new H2OCountedCompleter() { @Override public void compute2() { gridSearch(); tryComplete(); } },_total_models);
      return this;
    }

    /** @return the set of models covered by this grid search, some may be null
     *  if the search is in progress or otherwise incomplete. */
    public Model[] models() {
      Model[] ms = new Model[_total_models];
      int mcnt = 0;
      double[] hypers = new double[nHyperParms()];
      for( int[] hidx = new int[nHyperParms()]; hidx != null; hidx = nextModel(hidx) )
        ms[mcnt++] = model(hypers(hidx,hypers));
      return ms;
    }

    /** @return the stringified set of model parameters covered by this grid search */
    public String[][] toStrings() {
      int nhps = nHyperParms();
      String[][] sss = new String[_total_models][nhps];
      int mcnt = 0;
      double[] hypers = new double[nhps];
      for( int[] hidx = new int[nhps]; hidx != null; hidx = nextModel(hidx) ) {
        hypers = hypers(hidx,hypers);
        String[] ss = new String[nhps];
        for( int i=0; i<nhps; i++ )
          ss[i] = hyperToString(i,hypers[i]);
        sss[mcnt++] = ss;
      }
      return sss;
    }

    // Classic grid search over hyper-parameter space
    private void gridSearch() {
      double[] hypers = new double[nHyperParms()];
      for( int[] hidx = new int[nHyperParms()]; hidx != null; hidx = nextModel(hidx) ) {
        if( !isRunning() ) { cancel(); return; }
        buildModel(hypers(hidx,hypers));
      }
      done();
    }

    // Dumb iteration over the hyper-parameter space.
    // Return NULL at end
    private int[] nextModel( int[] hidx ) {
      // Find the next parm to flip
      int i;
      for( i=0; i<hidx.length; i++ )
        if( hidx[i]+1 < _hyperSearch[i].length )
          break;
      if( i==hidx.length ) return null; // All done, report null
      // Flip indices
      for( int j=0; j<i; j++ ) hidx[j]=0;
      hidx[i]++;
      return hidx;
    }
    private double[] hypers( int[] hidx, double[] hypers ) {
      for( int i=0; i<hidx.length; i++ )
        hypers[i] = _hyperSearch[i][hidx[i]];
      return hypers;            // Flow coding
    }
  }
}
