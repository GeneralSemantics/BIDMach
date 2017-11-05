package BIDMach.networks

import BIDMat.{Mat,SBMat,CMat,CSMat,DMat,Dict,FMat,IMat,LMat,HMat,GMat,GDMat,GIMat,GLMat,GSMat,GSDMat,SMat,SDMat}
import BIDMat.MatFunctions._
import BIDMat.SciFunctions._
import BIDMach.datasources._
import BIDMach.updaters._
import BIDMach.mixins._
import BIDMach.models._
import BIDMach._
import edu.berkeley.bid.CUMACH
import edu.berkeley.bid.CPUMACH
import jcuda.runtime.JCuda._
import scala.util.hashing.MurmurHash3
import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import java.text.SimpleDateFormat
import java.util.Calendar
import java.io.DataOutputStream
import java.io.DataInputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.Scanner
import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.duration.Duration

/**
 * Fast Word2Vec implementation for CPU and GPU with skip-grams and hierarchical softmax.
 * 
 * The input is an IMat with 2 rows. Each column holds a word ID (top row) and the corresponding sentence ID (second row). 
 * Options are:
 - nskip(5) the size of the skip-gram window.  
 - vocabSize(100000) the vocabulary size. The input matrix can contain larger word IDs, in which case those IDs are marked as OOV.  
 - freqs vector of word frequencies. 
 - iflip(false) true if word and sentence IDs flipped (sentence ID in first row, word ID in second).  
 - aopts:ADAGrad.Opts(null) set this to an ADAGRAD.Opts object to use integrated adagrad updates. 
 *
 * The code has the ability to build models larger than a single Java array, and bigger than a single node can store. 
 */

class Word2Vech(override val opts:Word2Vech.Opts = new Word2Vech.Options) extends Model(opts) {
  
  var firstPos = -1L;
  var wordtab:IMat = null;
  var minusone:IMat = null;
  var wordmask:IMat = null;
  var allones:IMat = null;
  var lastblock:IMat = null;
  var lastblockinds:IMat = null;
  var skipwords:IMat = null;
  var skipwordinds:IMat = null;
  var centerwords:IMat = null;
  var centerwordinds:IMat = null;
  var wordvecs:FMat = null;
  var nodevecs:FMat = null;
  var nfreqs:FMat = null;
  var itree:IMat = null;
  var iancestors:IMat = null;
//  var retEvalPos:GMat = null;
//  var retEvalNeg:GMat = null;
  var nfeats = 0;
  var ncols = 0;
  var expt = 0f;
  var vexp = 0f;
  var salpha = 0f;
  var maxCols = 0;
  var nmmats = 1;
  var fmm:Array[Array[Float]] = null;
  
  var ntimes = 12;
  var times:FMat = null;
  var delays:FMat = null;
  var log:ArrayBuffer[String] = null
  val dateFormat = new SimpleDateFormat("hh:mm:ss:SSS")

  
  def addTime(itime:Int, lasti:Int = -1) = {
    val t = toc
    times(itime) = t;
    if (itime > 0) {
    	delays(itime) += times(itime) - times(itime + lasti);
    } 
    val today = Calendar.getInstance().getTime()
    log += "Log: %s, GPU %d, event %d" format (dateFormat.format(today), if (useGPU) getGPU else 0, itime);
  }
  
  var test1:Mat = null;
  var test2:Mat = null;
  var test3:Mat = null;
  var test4:Mat = null;
  

  override def init() = {
    val mats = datasource.next;
	  nfeats = opts.vocabSize;
	  ncols = mats(0).ncols;
	  datasource.reset;
    
    nfreqs = zeros(2*nfeats-1,1);
    nfreqs((nfeats-1)->(2*nfeats-1),0) = FMat(opts.freqs / sum(opts.freqs));
    itree = izeros(2*nfeats-1, 1);
    
    iancestors = Word2Vech.buildHierarchy(nfreqs, itree);
	
    if (refresh) {
    	setmodelmats(new Array[Mat](2));
    	val mm0 = rand(opts.dim, nfeats+1);
    	mm0 ~ mm0 - 0.5f;
    	mm0 ~ mm0 / opts.dim;
      mm0(?,0) = 0f;
    	modelmats(0) = mm0;                                                   
    	modelmats(1) = zeros(opts.dim, nfeats);                               
    }
    modelmats(0) = convertMat(modelmats(0));                                   
    modelmats(1) = convertMat(modelmats(1)); 
    wordvecs = modelmats(0).asInstanceOf[FMat];
    nodevecs = modelmats(1).asInstanceOf[FMat];
    val nskip = opts.nskip;
    val nwindow = nskip * 2 + 1;
    val skipcol = icol((-nskip) to -1) on icol(1 to nskip);
    wordmask = convertIMat(skipcol * iones(1, ncols));                     // columns = distances from center word
    lastblock = convertIMat(izeros(2, nskip*2));
    lastblockinds = convertIMat(irow(ncols->(ncols+2*nskip)));
    skipwordinds = wordmask + convertIMat(irow(0->ncols)+nskip);
    centerwordinds = convertIMat(irow(opts.nskip->(ncols+nskip)));
    allones = convertIMat(iones(1, ncols+2*nskip));
    times = zeros(1, ntimes);
    delays = zeros(1, ntimes);
    log = ArrayBuffer();
  }
  
  def dobatch(gmats:Array[Mat], ipass:Int, pos:Long):Unit = {
    addTime(0);
    if (gmats(0).ncols == ncols) {
    	if (firstPos < 0) firstPos = pos;
    	val nsteps = 1f * pos / firstPos;
    	val gopts = opts.asInstanceOf[ADAGrad.Opts];
    	val lrate = gopts.lrate.dv.toFloat * math.pow(nsteps, - gopts.texp.dv).toFloat;
    	val (ancestors, skipwords) = wordMats(gmats, ipass, pos);
      val nodevectors = nodevecs(?, ancestors.contents);
      val skipwordvectors = wordvecs(?, skipwords.contents);
      // blockMultTN(depth, nskip*2, opts.dim, nodevectors, skipwordvectors, prods);
      // blockMultNN(opts.dim, nskip*2, depth, nodevectors, prods, skipdiffs);
      // blockMultNT(opts.dim, depth, nskip*2, skipwordvectors, prods, nodediffs);
    }
  }
  
  def evalbatch(gmats:Array[Mat], ipass:Int, pos:Long):FMat = {
  	addTime(0);
  	if (gmats(0).ncols == ncols) {
  		wordMats(gmats, ipass, pos);
  		row(1)
  	} else row(0);
  }
  
  def wordMats(mats:Array[Mat], ipass:Int, pos:Long):(IMat, IMat) = {
  
	  // Set OOV samples to -1
    val wordsens = lastblock \ mats(0).asInstanceOf[IMat];
    val words = if (opts.iflip) wordsens(1,?) else wordsens(0,?);
    val wgood = words < opts.vocabSize;      
    words ~ (wgood ∘ (words + 1)) - 1;                                        
    addTime(1);

    // Get the nearest sentence boundaries
    val sentencenum = if (opts.iflip) wordsens(0,?) else wordsens(1,?);        
    val lbsentence = - cumsumByKey(allones, sentencenum) + 1;
    val ubsentence = reverse(cumsumByKey(allones, reverse(sentencenum))) - 1;
    val lb = max(lbsentence(centerwordinds), -opts.nskip);
    val ub = min(ubsentence(centerwordinds), opts.nskip);
    test3 = lb
    test4 = ub
    addTime(2);
    
    // Build the center and skip word matrices, set words to -1 across sentence boundaries
    centerwords = words(centerwordinds);
    val ancestors = iancestors(?, centerwords);
    skipwords = words(skipwordinds);
    val pgood = (wordmask >= lb) ∘ (wordmask <= ub);
    skipwords ~ (pgood ∘ (skipwords + 1)) - 1; 
    addTime(3);
    
    lastblock = wordsens(?, lastblockinds);

    (ancestors, skipwords);
  }
  
 
  
  def trailingZeros(a:Long):Int = {
    var aa = a;
    var nz = 0;
    while ((aa & 1L) == 0) {
      aa = aa >> 1;
      nz += 1;
    }
    nz
  }

}

object Word2Vech  {
  trait Opts extends Model.Opts {
    var nskip = 5; 
    var vocabSize = 100000;
    var freqs:DMat = null;             // Frequencies of words in the vocab
    var iflip = false;                 // Whether rows are flipped. If false, first row is word id, second is sentence id. 

  }
  
  class Options extends Opts {}
  
  def buildHierarchy(nfreqs:FMat, itree:IMat):IMat = {
    buildTree(nfreqs, itree);
    val d = treeDepth(itree);
    val hierarchy = izeros(d, nfreqs.length);
    fillHierarchy(itree, d, hierarchy);
    hierarchy;
  }
  
  def buildTree(nfreqs:FMat, itree:IMat) = {
    val nfeats = (nfreqs.length + 1) / 2;
    val pq = collection.mutable.PriorityQueue[Tuple2[Float,Int] ]();
    var i = 0;
    while (i < nfeats) {
      pq += new Tuple2(- nfreqs.data(i + nfeats - 1), i + nfeats - 1);
      i += 1;
    }
    i -= 1;
    while (i > 0) {
      i -= 1;
      val v1 = pq.dequeue;
      val v2 = pq.dequeue;
      itree(v1._2, 0) = i;
      itree(v2._2, 0) = i;
      val newv = -(v1._1 + v2._1);
      nfreqs(i, 0) = newv;
      pq += new Tuple2(- newv, i);
    }
    itree(0,0) = -1;
  }
  
  def treeDepth(itree:IMat):Int = {
	  val nfeats = (itree.length + 1) / 2;
    var i = nfeats - 1;
    var depth = 0;
    while (i < 2*nfeats - 1) {
      var j = 0;
      var ptr = itree(i);
      while (ptr >= 0) {
        ptr = itree(ptr);
        j += 1;
      }
      depth = math.max(depth, j);
      i += 1;
    }
    depth;
  }
  
  def fillHierarchy(itree:IMat, depth:Int, hier:IMat) = {
    val nfeats = (itree.length + 1) / 2;
    var i = 0;
    while (i < nfeats) {
      var j = 0;
      var ptr = itree(i);
      while (ptr >= 0) {
        hier(j, i) = ptr;
        ptr = itree(ptr);
        j += 1;
      }
      while (j < depth) {
        hier(j, i) = -1;
        j += 1;
      }
      i += 1;
    }
    depth;
  }
  
  import jcuda.jcublas.cublasOperation._;
  import jcuda.jcublas.JCublas2._;
  import edu.berkeley.bid.CBLAS._
  import edu.berkeley.bid.CBLAS.TRANSPOSE._

  
  def blockMultTN(nr:Int, nc:Int, nk:Int, left:FMat, right:FMat, prod:FMat) = {
    (left, right, prod) match {
      case (gleft:GMat, gright:GMat, gprod:GMat) => {
        cublasSgemmStridedBatched(gleft.getHandle,
                          CUBLAS_OP_T, CUBLAS_OP_N,
                          nr, nc, nk,
                          GMat.pONE,
                          gleft.pdata, nk, gleft.nrows,
                          gright.pdata, nk, gright.nrows,
                          GMat.pZERO,
                          gprod.pdata, nr, gprod.nrows,
                          gleft.ncols);
        cudaDeviceSynchronize;
      }
      case _ => {
        blockSgemm(Trans, NoTrans, 
        		nr, nc, nk, left.ncols, 
        		left.data, 0, nk, left.nrows,
        		right.data, 0, nk, right.nrows,
        		prod.data, 0, nr, prod.nrows);
      }
    }
  }
  
  def blockMultNN(nr:Int, nc:Int, nk:Int, left:FMat, right:FMat, prod:FMat) = {
    (left, right, prod) match {
      case (gleft:GMat, gright:GMat, gprod:GMat) => {
        cublasSgemmStridedBatched(gleft.getHandle,
                          CUBLAS_OP_N, CUBLAS_OP_N,
                          nr, nc, nk,
                          GMat.pONE,
                          gleft.pdata, nr, gleft.nrows,
                          gright.pdata, nk, gright.nrows,
                          GMat.pZERO,
                          gprod.pdata, nr, gprod.nrows,
                          gleft.ncols);
        cudaDeviceSynchronize;
      }
      case _ => {
        blockSgemm(NoTrans, NoTrans, 
        		nr, nc, nk, left.ncols, 
        		left.data, 0, nr, left.nrows,
        		right.data, 0, nk, right.nrows,
        		prod.data, 0, nr, prod.nrows);
      }
    }
  }
  
   def blockMultNT(nr:Int, nc:Int, nk:Int, left:FMat, right:FMat, prod:FMat) = {
    (left, right, prod) match {
      case (gleft:GMat, gright:GMat, gprod:GMat) => {
        cublasSgemmStridedBatched(gleft.getHandle,
                          CUBLAS_OP_N, CUBLAS_OP_T,
                          nr, nc, nk,
                          GMat.pONE,
                          gleft.pdata, nr, gleft.nrows,
                          gright.pdata, nc, gright.nrows,
                          GMat.pZERO,
                          gprod.pdata, nr, gprod.nrows,
                          gleft.ncols);
        cudaDeviceSynchronize;
      }
      case _ => {
        blockSgemm(NoTrans, Trans, 
        		nr, nc, nk, left.ncols, 
        		left.data, 0, nr, left.nrows,
        		right.data, 0, nc, right.nrows,
        		prod.data, 0, nr, prod.nrows);
      }
    }
  }
  
  def mkModel(fopts:Model.Opts) = {
    new Word2Vec(fopts.asInstanceOf[Word2Vec.Opts])
  }
  
  def mkUpdater(nopts:Updater.Opts) = {
    new ADAGrad(nopts.asInstanceOf[ADAGrad.Opts])
  } 
  
  def mkRegularizer(nopts:Mixin.Opts):Array[Mixin] = {
    Array(new L1Regularizer(nopts.asInstanceOf[L1Regularizer.Opts]))
  }
    
  class LearnOptions extends Learner.Options with Word2Vec.Opts with MatSource.Opts with ADAGrad.Opts with L1Regularizer.Opts;
  
  def learner(mat0:Mat, targ:Mat) = {
    val opts = new LearnOptions;
    opts.batchSize = math.min(100000, mat0.ncols/30 + 1);
  	val nn = new Learner(
  	    new MatSource(Array(mat0, targ), opts), 
  	    new Word2Vec(opts), 
  	    null,
  	    null, 
  	    null,
  	    opts)
    (nn, opts)
  }
  
  class FDSopts extends Learner.Options with Word2Vec.Opts with FileSource.Opts with ADAGrad.Opts with L1Regularizer.Opts
  
  def learner(fn1:String):(Learner, FDSopts) = learner(List(FileSource.simpleEnum(fn1,1,0)));
  
  def learner(fnames:List[(Int)=>String]):(Learner, FDSopts) = {   
    val opts = new FDSopts
    opts.fnames = fnames;
    opts.batchSize = 100000;
    opts.eltsPerSample = 500;
    implicit val threads = threadPool(4);
    val ds = new FileSource(opts);
  	val nn = new Learner(
  			ds, 
  	    new Word2Vec(opts), 
  	    null,
  	    null, 
  	    null,
  	    opts)
    (nn, opts)
  }
  
  def predictor(model0:Model, mat0:Mat, preds:Mat):(Learner, LearnOptions) = {
    val model = model0.asInstanceOf[Word2Vec];
    val opts = new LearnOptions;
    opts.batchSize = math.min(10000, mat0.ncols/30 + 1)
    if (mat0.asInstanceOf[AnyRef] != null) opts.putBack = 1;
    
    val newmod = new Word2Vec(opts);
    newmod.refresh = false;
    newmod.copyFrom(model);
    val mopts = model.opts.asInstanceOf[Word2Vec.Opts];
    opts.dim = mopts.dim;
    opts.vocabSize = mopts.vocabSize;
    opts.nskip = mopts.nskip;
    opts.nneg = mopts.nneg;
    opts.nreuse = mopts.nreuse;
    val nn = new Learner(
        new MatSource(Array(mat0, preds), opts), 
        newmod, 
        null,
        null,
        null,
        opts);
    (nn, opts)
  }
  
   def predictor(model0:Model, mat0:Mat):(Learner, LearnOptions) = {
    val model = model0.asInstanceOf[Word2Vec];
    val opts = new LearnOptions;
    opts.batchSize = math.min(10000, mat0.ncols/30 + 1)    
    val newmod = new Word2Vec(opts);
    newmod.refresh = false;
    newmod.copyFrom(model);
    val mopts = model.opts.asInstanceOf[Word2Vec.Opts];
    opts.dim = mopts.dim;
    opts.vocabSize = mopts.vocabSize;
    opts.nskip = mopts.nskip;
    opts.nneg = mopts.nneg;
    opts.nreuse = mopts.nreuse;
    opts.maxArraySize = mopts.maxArraySize;
    opts.iSlice = mopts.iSlice;
    opts.nSlices = mopts.nSlices;
    opts.nHeadTerms = mopts.nHeadTerms;
    val nn = new Learner(
        new MatSource(Array(mat0), opts), 
        newmod, 
        null,
        null,
        null,
        opts);
    (nn, opts)
  }
  
  class LearnParOptions extends ParLearner.Options with Word2Vec.Opts with FileSource.Opts with ADAGrad.Opts;
  
  def learnPar(fn1:String):(ParLearnerF, LearnParOptions) = {learnPar(List(FileSource.simpleEnum(fn1,1,0)))}
  
  def learnPar(fnames:List[(Int) => String]):(ParLearnerF, LearnParOptions) = {
    val opts = new LearnParOptions;
    opts.batchSize = 10000;
    opts.lrate = 1f;
    opts.fnames = fnames;
    implicit val threads = threadPool(4)
    val nn = new ParLearnerF(
        new FileSource(opts), 
        () => mkModel(opts), 
  	    null, 
  	    () => mkUpdater(opts),
  	    null,
        opts)
    (nn, opts)
  }
  
  // Read a Google Word2Vec model file in binary or text format. 
  
  def readGoogleW2V(fname:String, dict:Dict, n:Int, binary:Boolean = false):FMat = {
    val ins = HMat.getInputStream(fname, 0);
    val din = new DataInputStream(ins);
    val sin = new Scanner(din);
    val header = sin.nextLine
    val dims = header.split(" ");
    val nr = dims(0).toInt;
    val dim = dims(1).toInt;
    val model = FMat(dim, n);

    var i = 0;
    while (i < nr) {
    	val word = sin.next;
    	val icol = dict(word);
    	val saveIt = (icol >= 0 && icol < n);
    	var j = 0;
    	while (j < dim) {
    		val v = if (binary) {
    			din.readFloat;
    		} else {
    			sin.nextFloat;
    		}
    		if (saveIt) model(j, icol) = v;
    		j += 1;
    	}
    	sin.nextLine;
    	i += 1;
    	if (i % 1000 == 0) println("i=%d %s" format (i, word))
    }
    model;
  }
  
  // Write a Google Word2Vec model file in binary or text format. 
  
  def saveGoogleW2V(dict:CSMat, mod:FMat, fname:String, binary:Boolean = false) = {
    val nwords = if (mod.ncols < dict.length) {
      mod.ncols;
    } else {
      println("Warning: dictionary is smaller than model word count - you should reduce vocabSize in the Word2Vec model");
      dict.length;
    }
  	val outs = HMat.getOutputStream(fname, 0);
  	val dout = new DataOutputStream(outs);
  	val cr = String.format("\n");
  	dout.writeBytes(nwords.toString + " " + mod.nrows.toString + cr);
  	var i = 0;
  	while (i < nwords) {
		dout.writeBytes(dict(i)+ " ");
  		var nwritten = 0;
  		var j = 0;
  		while (j < mod.nrows) {
  			if (binary) {
  			  dout.writeFloat(mod(j,i));
  			} else {
  			  dout.writeBytes("%g " format mod(j,i));
  			}
  			j += 1;
  		}
  		i += 1;
  		dout.writeBytes(cr);
  	}
  	dout.close;
};

}

