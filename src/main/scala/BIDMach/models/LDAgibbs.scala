package BIDMach.models

import BIDMat.{Mat,SBMat,CMat,DMat,FMat,IMat,HMat,GMat,GIMat,GSMat,SMat,SDMat}
import BIDMat.MatFunctions._
import BIDMat.SciFunctions._
//import edu.berkeley.bid.CUMAT

import BIDMach.datasources._
import BIDMach.updaters._
import BIDMach._

/**
 * Latent Dirichlet Model using repeated Gibbs sampling. 
 * 
 * Extends Factor Model Options with:
 - dim(256): Model dimension
 - uiter(5): Number of iterations on one block of data
 - alpha(0.001f) Dirichlet prior on document-topic weights
 - beta(0.0001f) Dirichlet prior on word-topic weights
 - nsamps(100) the number of repeated samples to take
 *
 * Other key parameters inherited from the learner, datasource and updater:
 - blockSize: the number of samples processed in a block
 - power(0.3f): the exponent of the moving average model' = a dmodel + (1-a)*model, a = 1/nblocks^power
 - npasses(10): number of complete passes over the dataset
 *     
 * '''Example:'''
 * 
 * a is a sparse word x document matrix
 * {{{
 * val (nn, opts) = LDAgibbs.learn(a)
 * opts.what             // prints the available options
 * opts.uiter=2          // customize options
 * nn.run                // run the learner
 * nn.modelmat           // get the final model
 * nn.datamat            // get the other factor (requires opts.putBack=1)
 *  
 * val (nn, opts) = LDAgibbs.learnPar(a) // Build a parallel learner
 * opts.nthreads = 2     // number of threads (defaults to number of GPUs)
 * nn.run                // run the learner
 * nn.modelmat           // get the final model
 * nn.datamat            // get the other factor
 * }}}
 * 
 */

class LDAgibbs(override val opts:LDAgibbs.Opts = new LDAgibbs.Options) extends FactorModel(opts) {
 
  var mm:Mat = null
  var alpha:Mat = null 
  var traceMem = false
  
  override def init(datasource:DataSource) = {
    super.init(datasource)
    mm = modelmats(0)
    //modelmats = new Array[Mat](2)
    modelmats = new Array[Mat](3)
    modelmats(0) = mm
    modelmats(1) = mm.ones(mm.nrows, 1)
    modelmats(2) = mm.ones(mm.nrows, mm.ncols)
    updatemats = new Array[Mat](2)
    updatemats(0) = mm.zeros(mm.nrows, mm.ncols)
    updatemats(1) = mm.zeros(mm.nrows, 1)
  }
  
  def uupdate(sdata:Mat, user:Mat, ipass: Int):Unit = {
    
    	if (opts.putBack < 0 || ipass == 0) user.set(1f)
    	
    	val mnew = updatemats(0)
    	mnew.set(0f)
    	
        for (i <- 0 until opts.uiter) yield {
        val preds = DDS(mm, user, sdata)	
        if (traceMem) println("uupdate %d %d %d, %d %f %d" format (mm.GUID, user.GUID, sdata.GUID, preds.GUID, GPUmem._1, getGPU))
    	val dc = sdata.contents
    	val pc = preds.contents
    	pc ~ pc / dc
    	
    	val unew = user*0

    	//val nsamps = GMat(opts.tempfunc(opts.nsampsi, ipass))
    	//val nsamps = GMat(100 * ones(mm.ncols, 1))
    	val nsamps = modelmats(2);
        
    	LDAgibbs.LDAsample(mm, user, mnew, unew, preds, nsamps)
        
    	if (traceMem) println("uupdate %d %d %d, %d %d %d %d %f %d" format (mm.GUID, user.GUID, sdata.GUID, preds.GUID, dc.GUID, pc.GUID, unew.GUID, GPUmem._1, getGPU))
    	user ~ unew + opts.alpha
    	}
  
  }
  
  def mupdate(sdata:Mat, user:Mat, ipass: Int):Unit = {
	val um = updatemats(0)
	um ~ um + opts.beta 
  	sum(um, 2, updatemats(1))
  }
  
  def evalfun(sdata:Mat, user:Mat):FMat = {  
  	val preds = DDS(mm, user, sdata)
  	val dc = sdata.contents
  	val pc = preds.contents
  	max(opts.weps, pc, pc)
  	ln(pc, pc)
  	val sdat = sum(sdata,1)
  	val mms = sum(mm,2)
  	val suu = ln(mms ^* user)
  	if (traceMem) println("evalfun %d %d %d, %d %d %d, %d %f" format (sdata.GUID, user.GUID, preds.GUID, pc.GUID, sdat.GUID, mms.GUID, suu.GUID, GPUmem._1))
  	val vv = ((pc ddot dc) - (sdat ddot suu))/sum(sdat,2).dv
  	row(vv, math.exp(-vv))
  }
}

object LDAgibbs  {
  
  trait Opts extends FactorModel.Opts {
    var alpha = 0.001f
    var beta = 0.0001f
    var nsamps = 1
    //var nsampsi = nsamps * ones(dim, 1)
    var tempfunc = (nsamps: Mat, ipass: Int) => nsamps * ipass
  }
  
  class Options extends Opts {}
  
   def LDAsample(A:Mat, B:Mat, AN:Mat, BN:Mat, C:Mat, nsamps:Float):Unit = {
    (A, B, AN, BN, C) match {
     case (a:GMat, b:GMat, an:GMat, bn:GMat, c:GSMat) => GSMat.LDAgibbs(a, b, an, bn, c, nsamps):Unit
     case _ => throw new RuntimeException("LDAgibbs: arguments not recognized")
    }
  }
   
   def LDAsample(A:Mat, B:Mat, AN:Mat, BN:Mat, C:Mat, nsamps:Mat):Unit = {
     
    (A, B, AN, BN, C, nsamps) match {
     case (a:GMat, b:GMat, an:GMat, bn:GMat, c:GSMat, nsamps:GMat) => GSMat.LDAgibbsv(a, b, an, bn, c, nsamps):Unit
     case _ => throw new RuntimeException("LDAgibbs: arguments not recognized")
    }
  }
  
  def mkGibbsLDAmodel(fopts:Model.Opts) = {
  	new LDAgibbs(fopts.asInstanceOf[LDAgibbs.Opts])
  }
  
  def mkUpdater(nopts:Updater.Opts) = {
  	new IncNorm(nopts.asInstanceOf[IncNorm.Opts])
  } 
  
  /*
   * This learner uses stochastic updates (like the standard LDA model)
   */
  def learn(mat0:Mat, d:Int = 256) = {
    class xopts extends Learner.Options with LDAgibbs.Opts with MatDS.Opts with IncNorm.Opts
    val opts = new xopts
    opts.dim = d
    opts.putBack = 1
    opts.blockSize = math.min(100000, mat0.ncols/30 + 1)
  	val nn = new Learner(
  	    new MatDS(Array(mat0:Mat), opts), 
  			new LDAgibbs(opts), 
  			null,
  			new IncNorm(opts), opts)
    (nn, opts)
  }
  
  /*
   * Batch learner
   */
  def learnBatch(mat0:Mat, d:Int = 256) = {
    class xopts extends Learner.Options with LDAgibbs.Opts with MatDS.Opts with BatchNorm.Opts
    val opts = new xopts
    opts.dim = d
    opts.putBack = 1
    opts.uiter = 2
    opts.blockSize = math.min(100000, mat0.ncols/30 + 1)
    val nn = new Learner(
        new MatDS(Array(mat0:Mat), opts), 
        new LDAgibbs(opts), 
        null, 
        new BatchNorm(opts),
        opts)
    (nn, opts)
  }
  
  /*
   * Parallel learner with matrix source
   */ 
  def learnPar(mat0:Mat, d:Int = 256) = {
    class xopts extends ParLearner.Options with LDAgibbs.Opts with MatDS.Opts with IncNorm.Opts
    val opts = new xopts
    opts.dim = d
    opts.putBack = -1
    opts.uiter = 5
    opts.blockSize = math.min(100000, mat0.ncols/30/opts.nthreads + 1)
    opts.coolit = 0 // Assume we dont need cooling on a matrix input
    val nn = new ParLearnerF(
        new MatDS(Array(mat0:Mat), opts), 
        opts, mkGibbsLDAmodel _, 
            null, null, 
            opts, mkUpdater _,
            opts)
    (nn, opts)
  }
  
  /*
   * Parallel learner with multiple file datasources
   */
  def learnFParx(
      nstart:Int=FilesDS.encodeDate(2012,3,1,0), 
      nend:Int=FilesDS.encodeDate(2012,12,1,0), 
      d:Int = 256
      ) = {
    class xopts extends ParLearner.Options with LDAgibbs.Opts with SFilesDS.Opts with IncNorm.Opts
    val opts = new xopts
    opts.dim = d
    opts.npasses = 4
    opts.resFile = "/big/twitter/test/results.mat"
    val nn = new ParLearnerxF(
        null, 
            (dopts:DataSource.Opts, i:Int) => SFilesDS.twitterWords(nstart, nend, opts.nthreads, i), 
            opts, mkGibbsLDAmodel _, 
            null, null, 
        opts, mkUpdater _,
        opts
    )
    (nn, opts)
  }
  
  /* 
   * Parallel learner with single file datasource
   */ 
  def learnFPar(
      nstart:Int=FilesDS.encodeDate(2012,3,1,0), 
      nend:Int=FilesDS.encodeDate(2012,12,1,0), 
      d:Int = 256
      ) = {   
    class xopts extends ParLearner.Options with LDAgibbs.Opts with SFilesDS.Opts with IncNorm.Opts
    val opts = new xopts
    opts.dim = d
    opts.npasses = 4
    opts.resFile = "/big/twitter/test/results.mat"
    val nn = new ParLearnerF(
        SFilesDS.twitterWords(nstart, nend), 
        opts, mkGibbsLDAmodel _, 
        null, null, 
        opts, mkUpdater _,
        opts
    )
    (nn, opts)
  }
  
}

