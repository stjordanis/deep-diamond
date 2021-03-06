;;   Copyright (c) Dragan Djuric. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) or later
;;   which can be found in the file LICENSE at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns uncomplicate.diamond.internal.dnnl.core
  (:require [uncomplicate.commons
             [core :refer [let-release with-release wrap extract]]
             [utils :refer [enc-keyword direct-buffer capacity dragan-says-ex mask]]]
            [uncomplicate.diamond.internal.dnnl
             [impl :refer :all]
             [constants :refer :all]
             [protocols :refer :all]])
  (:import org.bytedeco.javacpp.Pointer
           org.bytedeco.dnnl.global.dnnl
           [org.bytedeco.dnnl dnnl_engine dnnl_memory_desc_t dnnl_exec_arg_t]))

;; ===================== Engine ===============================================

(defn engine
  "Creates an engine for the device `id` of the specified keyword `kind`.

   Supported engine kinds are `:cpu`, `:gpu`, and `:any`. The default kind is `:cpu`.
   Engine has to be `release`d.

   Throws an ExceptionInfo if the `id` does not correspond to a physical device
   or if `kind` is not supported."
  ([^long id kind]
   (wrap (engine* id (enc-keyword dnnl-engine-kind kind))))
  ([^long id]
   (wrap (engine* id)))
  ([]
   (engine 0)))

(defn engine-count
  "Returns the number of physical engines of the specified `kind` (`:cpu`, `:gpu`, `:any`).

  Throws an ExceptionInfo if `kind` is not supported."
  (^long []
   (engine-count*))
  (^long [kind]
   (engine-count* (enc-keyword dnnl-engine-kind kind))))

(defn engine-kind
  "Returns engine's kind as a keyword. Typical values are `:gpu` and `:cpu`.

  Throws an ExceptionInfo if `kind` is not supported."
  ([eng]
   (dec-engine-kind (engine-kind* (extract eng)))))

;; ===================== Stream ===============================================

(defn stream
  "Creates a stream for executing primitive operations for engine `eng`.

  Stream execution can be further specified by `flags`, defined in the
  [[constants/dnnl-stream-flags]].
  Stream has to be `release`d."
  [eng & flags]
  (wrap (if flags
          (stream* (extract eng) (mask dnnl-stream-flags flags))
          (stream* (extract eng)))))

(defn wait!
  "Waits until stream `s` completes execution of all queued operations."
  [strm]
  (wait* (extract strm))
  strm)

(defn execute!
  "Queues the operation primitive `p` for execution in stream `strm`.

  Returns `strm`. Throws an ExceptionInfo if the DNNL stream is not valid,
  or the primitive cannot be executed."
  [strm p args]
  (execute* (extract strm) (extract p) args)
  strm)


;; ===================== Memory ===============================================

(defn memory-desc
  "Creates an engine-agnostic, logical, description of data, based on dimensions,
  data type and data format.

  `dims` is a Clojure vector of positive numbers representing dimensions in the
  `:abcdef` format, regardless of the physical layout of dimensions.
  `data-type` is a keyword that specifies one of the supported types of data,
  defined in [[`constants/dnnl-data-type`]] (`:float`, `:int`, etc.)
  `format` specifies an (optional) physical layout as a keyword, choosing one
  of [[`constants/dnnl-format`]] (`:nchw`, `:acdeb`, `:any`, etc.), or through
  strides specified as a Clojure vector of positive numbers that match logical
  dimensions.

  Examples:

  (memory-desc [2 3] :float :nc)

  (memory-desc [2 3 4 5] :float [120 3 4 5])
  "
  ([dims data-type format]
   (memory-desc* (if (keyword? format)
                   (enc-keyword dnnl-format format)
                   (long-array format))
                 (long-array dims) (enc-keyword dnnl-data-type data-type)))
  ([dims format]
   (memory-desc dims :float format))
  ([dims]
   (memory-desc dims :float :any)))

(defn submemory-desc
  "Creates a (sub)memory section of a memory object, using the specified
  shape `dims`, and `offsets` vectors."
  ([parent-desc dims offsets]
   (submemory-desc* (desc parent-desc) (long-array dims) (long-array offsets)))
  ([parent-desc dim]
   (if (number? dim)
     (submemory-desc* (desc parent-desc) dim)
     (let [ds (long-array dim)]
       (submemory-desc* (desc parent-desc) ds (long-array (alength ds)))))))

(defn equal-desc?
  "Compares two memory descriptors for logical equality.

  Two descriptors may be equal even though the objects are not
  equal nor identical in the JVM sense.
  "
  [x y]
  (let [x (desc x)
        y (desc y)]
    (or (= x y) (= 1 (dnnl/dnnl_memory_desc_equal x y)))))

(defn data-type
  "Queries the data type of a memory descriptor."
  [mem-desc]
  (dec-data-type (data-type* (desc mem-desc))))

(defn ndims
  "Queries the number of dimensions of a memory descriptor."
  ^long [mem-desc]
  (.ndims ^dnnl_memory_desc_t (desc mem-desc)))

(defn dims
  "Queries the dimensions of a memory descriptor."
  [mem-desc]
  (vec (dims* (desc mem-desc))))

(defn size
  "Queries the mem-desc for its dimensions."
  ^long [mem-desc]
  (dnnl/dnnl_memory_desc_get_size (desc mem-desc)))

(defn strides
  "Queries the strides of a memory descriptor."
  [mem-desc]
  (vec (strides* (desc mem-desc))))

(defn memory
  "An engine-specific memory handle for a raw buffer and a matching descriptor.

  `eng` a DNNL engine that controls the context.
  `mem-desc` logical memory descriptor.
  `buf` Java's DirectByteBuffer instance.
  `master` indicates whether this memory object handles the life cycle of `buf`."
  ([eng mem-desc buf master]
   (if (<= (size (desc mem-desc)) (capacity buf))
     (memory* (desc mem-desc) (extract eng) buf master)
     (dragan-says-ex "The buffer has to be large enough for mem-desc"
                     {:size (size (desc mem-desc)) :capacity (capacity buf)})))
  ([eng mem-desc buf]
   (memory eng mem-desc buf false))
  ([eng mem-desc]
   (let-release [buf (direct-buffer (size (desc mem-desc)))]
     (memory* (desc mem-desc) (extract eng) buf true))))

(defn offset!
  "Sets the starting position in the buffer that the memory object `mem` controls."
  [mem ^long n]
  (let [p (ptr mem)]
    (if (and (<= 0 n) (<= n (.capacity ^Pointer p)))
      (with-check (dnnl/dnnl_memory_set_data_handle
                   (extract mem) (.position ^Pointer p n))
        mem)
      (dragan-says-ex "There is not enough capacity in the underlying buffer for this offset."
                      {:n n :requested n :available (.capacity ^Pointer p)}))))

(defn offset
  "Gets the starting position in the buffer that the memory object `mem` controls."
  [mem]
  (.position ^Pointer (ptr mem)))

(defn get-engine
  "Returns the engine context of the memory object `mem`."
  [mem]
  (wrap (get-engine* (extract mem))))

;; ===================== Desc =================================================

(defn primitive-kind
  "Queries `desc` for the kind of primitive that it describes, returned as
  keyword.

  Result is one of the keywords defined in [[constants/dec-primitive-kind]],
  typically `:inner-product`, `:convolution`, `:elementwise`, etc."
  [desc]
  (dec-primitive-kind (primitive-kind* desc)))

(defn primitive-desc
  "Creates a primitive descriptor from the operation descriptor `desc`,
  optionally using a hint provided by a complementary primitive descriptor
  `hint-pd` (in case of backward propagation, for example), and additonal
  optional attribute `attr`."
  ([eng desc]
   (wrap (primitive-desc* desc (extract eng))))
  ([eng desc hint-pd]
   (wrap (primitive-desc* desc (extract eng) (extract hint-pd))))
  ([eng desc hint-pd attr]
   (wrap (primitive-desc* desc (extract eng) (extract hint-pd) (extract attr)))))

;; ===================== Primitive ============================================

(defn primitive
  "Creates a primitive from the primitive descriptor `pd`.

  Primitive encapsulates a pre-generated computation optimized for particular
  data shapes defined in the primitive descriptor. Usually, such primitive is
  executed many times with the data of these shapes, while the preparation cost
  is paid only at the time of creation.

  Primitive is a function with execution context (state). In addition to immutable
  state such as input and output shape and data type, it could require a mutable
  temporary work memory buffer that is called scratchpad in DNNL terminology.

  For more info about DNNL's concepts, see
  [the official DNNL guide](https://intel.github.io/mkl-dnn/dev_guide_basic_concepts.html).
  "
  [pd]
  (wrap (primitive* (extract pd))))

;; =================== Query ====================================================

(defn src-md
  "Queries the primitive descriptor `pd` for the source (input)."
  [pd]
  (query-md* (extract pd) dnnl/dnnl_query_src_md))

(defn diff-src-md
  "Queries the primitive descriptor `pd` for the gradient of the source (input)."
  [pd]
  (query-md* (extract pd) dnnl/dnnl_query_diff_src_md))

(defn weights-md
  "Queries the primitive descriptor `pd` for the weights."
  [pd]
  (query-md* (extract pd) dnnl/dnnl_query_weights_md))

(defn diff-weights-md
  "Queries the primitive descriptor `pd` for the gradient of the weights."
  [pd]
  (query-md* (extract pd) dnnl/dnnl_query_diff_weights_md))

(defn dst-md
  "Queries the primitive descriptor `pd` for the destination (output)."
  [pd]
  (query-md* (extract pd) dnnl/dnnl_query_dst_md))

(defn diff-dst-md
  "Queries the primitive descriptor `pd` for the gradient of the destination (output)."
  [pd]
  (query-md* (extract pd) dnnl/dnnl_query_diff_dst_md))

(defn workspace-md
  "Queries the primitive descriptor `pd` for the workspace (scratchpad)."
  [pd]
  (query-md* (extract pd) dnnl/dnnl_query_workspace_md))

;; =================== Etlwise ==================================================

(defn eltwise-fwd-desc
  "Creates a forward descriptor of an operation that is applied to
  every element of a tensor.

  * `prop-kind`: the kind of propagation: `:inference`, `training`, or `:scoring`
  (defined in `[[constants/dnnl-forward-prop-kind]]`)
  * `alg-kind`: operation algorithm, such as `:relu` or `:logistic`
  (defined in `[[constants/dnnl-eltwise-alg-kind]]`)
  * `mem-desc`: the descriptor that defines memory layout of the data
  * `alpha`, and `beta`: optional coefficients, depending on `alg-kind`."
  ([prop-kind alg-kind mem-desc alpha beta]
   (eltwise-forward-desc* (enc-keyword dnnl-forward-prop-kind prop-kind)
                          (enc-keyword dnnl-eltwise-alg-kind alg-kind)
                          (desc mem-desc) alpha beta))
  ([prop-kind alg-kind mem-desc]
   (eltwise-forward-desc* (enc-keyword dnnl-forward-prop-kind prop-kind)
                          (enc-keyword dnnl-eltwise-alg-kind alg-kind)
                          (desc mem-desc) 0.0 0.0)))

(defn eltwise-bwd-desc
  "Creates a backward descriptor of an operation that is applied to
  every element of a tensor. Used only during the training.

  * `alg-kind`: operation algorithm, such as `:relu` or `:logistic`
  (defined in `[[constants/dnnl-eltwise-alg-kind]]`)
  * `diff-desc`: the source memory descriptor
  * `src-desc`: the source memory descriptor
  * `dst-desc`: the destination memory descriptor
  * `alpha`, and `beta`: optional coefficients, depending on `alg-kind`."
  ([alg-kind diff-desc src-desc alpha beta]
   (eltwise-backward-desc* (enc-keyword dnnl-eltwise-alg-kind alg-kind)
                           (desc diff-desc) (desc src-desc) alpha beta))
  ([alg-kind diff-desc src-desc]
   (eltwise-backward-desc* (enc-keyword dnnl-eltwise-alg-kind alg-kind)
                           (desc diff-desc) (desc src-desc) 0.0 0.0)))

(defn eltwise-args
  "Creates DNNL's data structure that holds arguments as required by
  elementwise operations."
  ([src-and-dst]
   (let-release [args (dnnl_exec_arg_t. 2)]
     (args* args 0 dnnl/DNNL_ARG_SRC (extract src-and-dst))
     (args* args 1 dnnl/DNNL_ARG_DST (extract src-and-dst))))
  ([src dst]
   (let-release [args (dnnl_exec_arg_t. 2)]
     (args* args 0 dnnl/DNNL_ARG_SRC (extract src))
     (args* args 1 dnnl/DNNL_ARG_DST (extract dst))))
  ([src diff-dst diff-src]
   (let-release [args (dnnl_exec_arg_t. 3)]
     (args* args 0 dnnl/DNNL_ARG_SRC (extract src))
     (args* args 1 dnnl/DNNL_ARG_DIFF_DST (extract diff-dst))
     (args* args 2 dnnl/DNNL_ARG_DIFF_SRC (extract diff-src)))))

;; ======================= Sum ============================================================

(defn sum!
  "Scales a single `dst`, or sums scaled entries of more tensors elementwise.

  This operation changes `dst`. All sources and destinations have to be of
  the same shape.

  BEWARE: if `dst` and one of the `src`s are identical, this source has to
  be the first `src` argument, due to how DNNL algorithm works internally,
  or result would be incorrect!

  `eng`: the computing context engine
  `scale`: a floating point scale for the first source

  If only a single tensor is provided, computes dst = scale * dst.
  `dst`: the source and destination tensor

  Otherwise, computes dst = scale * src + scale-srcs[0] * scale-srcs[1] etc.
  `dst`: the source and destination tensor
  `src`: the first source tensor
  `scale-srcs`: a sequence of `scale1,` `src1`, `scale2`, `src2`, etc.

  Example:
  (sum eng md 2.0 md 3.0 md)
  "
  ([eng scale dst]
   (wrap (sum* (desc dst) (float-array [scale]) (desc dst) (extract eng))))
  ([eng dst scale src & scale-srcs]
   (let [srcs (mapv desc (cons src (take-nth 2 (rest scale-srcs))))
         n (count srcs)]
     (let-release [s (dnnl_memory_desc_t. n)]
       (dotimes [i n]
         (.position s i)
         (.put s (srcs i)))
       (wrap (sum* (desc dst)
                   (float-array (cons scale (take-nth 2 scale-srcs)))
                   s (extract eng)))))))

;; ========================= Execution Arguments =======================================

(defn args
  "Creates DNNL's data structure that holds arguments for various
  operations that accept one destination and one or multiple sources."
  ([src-and-dst]
   (let-release [args (dnnl_exec_arg_t. 2)]
     (args* args 0 dnnl/DNNL_ARG_MULTIPLE_SRC (extract src-and-dst))
     (args* args 1 dnnl/DNNL_ARG_DST (extract src-and-dst))))
  ([dst src]
   (let-release [args (dnnl_exec_arg_t. 2)]
     (args* args 0 dnnl/DNNL_ARG_DST (extract dst))
     (args* args 1 dnnl/DNNL_ARG_MULTIPLE_SRC (extract src))))
  ([dst src0 src1]
   (let-release [args (dnnl_exec_arg_t. 3)]
     (args* args 0 dnnl/DNNL_ARG_DST (extract dst))
     (args* args 1 dnnl/DNNL_ARG_MULTIPLE_SRC (extract src0))
     (args* args 2 (inc dnnl/DNNL_ARG_MULTIPLE_SRC) (extract src1))))
  ([dst src0 src1 & srcs]
   (let-release [args (dnnl_exec_arg_t. 2)]
     (args* args 0 dnnl/DNNL_ARG_DST (extract dst))
     (args* args 1 dnnl/DNNL_ARG_MULTIPLE_SRC (extract src0))
     (args* args 2 (inc dnnl/DNNL_ARG_MULTIPLE_SRC) (extract src1))
     (doall (map #(args* args (+ dnnl/DNNL_ARG_MULTIPLE_SRC (int %2)) (extract %1))
                 srcs (range 3 30)))
     args)))

(defn fwd-args
  "Creates DNNL's data structure that holds arguments as required by
  forward operations."
  ([src dst]
   (let-release [args (dnnl_exec_arg_t. 2)]
     (args* args 0 dnnl/DNNL_ARG_SRC (extract src))
     (args* args 1 dnnl/DNNL_ARG_DST (extract dst))))
  ([src dst workspace]
   (let-release [args (dnnl_exec_arg_t. 3)]
     (args* args 0 dnnl/DNNL_ARG_SRC (extract src))
     (args* args 1 dnnl/DNNL_ARG_DST (extract dst))
     (args* args 2 dnnl/DNNL_ARG_WORKSPACE (extract workspace))))
  ([src weights bias dst]
   (let-release [args (dnnl_exec_arg_t. 4)]
     (args* args 0 dnnl/DNNL_ARG_SRC (extract src))
     (args* args 1 dnnl/DNNL_ARG_WEIGHTS (extract weights))
     (args* args 2 dnnl/DNNL_ARG_BIAS (extract bias))
     (args* args 3 dnnl/DNNL_ARG_DST (extract dst)))))

(defn bwd-args
  ([diff-dst weights diff-src]
   (let-release [args (dnnl_exec_arg_t. 3)]
     (args* args 0 dnnl/DNNL_ARG_DIFF_DST (extract diff-dst))
     (args* args 1 dnnl/DNNL_ARG_WEIGHTS (extract weights))
     (args* args 2 dnnl/DNNL_ARG_DIFF_SRC (extract diff-src))))
  ([src diff-dst diff-weights diff-bias]
   (let-release [args (dnnl_exec_arg_t. 4)]
     (args* args 0 dnnl/DNNL_ARG_SRC (extract src))
     (args* args 1 dnnl/DNNL_ARG_DIFF_DST (extract diff-dst))
     (args* args 2 dnnl/DNNL_ARG_DIFF_WEIGHTS (extract diff-weights))
     (args* args 3 dnnl/DNNL_ARG_DIFF_BIAS (extract diff-bias)))))

;; ========================= Reorder ==================================================

(defn reorder
  "Copies data across engines, between physical memory formats, keeping the
  logical structure of the tensor."
  ([input-eng input output-eng output]
   (wrap (reorder* (desc input) (extract input-eng) (desc output) (extract output-eng))))
  ([eng input output]
   (reorder eng input eng output)))

;; ======================== Inner Product ======================================================

(defn inner-product-fwd-desc
  "Creates a descriptor for the forward phase of the inner product operation,
  which computes `dst <- src * weights + bias`.

  `prop-kind`: one of the values defined in [[constants/dnnl-forward-prop-kind]]
  (`:inference`, `:training`, `:scoring`).
  `src-desc`: descriptor of the source (input) memory.
  `weights-desc`: descriptor of the weights memory.
  `bias-desc`: descriptor of the bias memory.
  `dst-desc`: descripror of the destination (output) memory.
  "
  [prop-kind src-desc weights-desc bias-desc dst-desc]
  (inner-product-forward-desc* (enc-keyword dnnl-forward-prop-kind prop-kind)
                               (desc src-desc) (desc weights-desc)
                               (desc bias-desc) (desc dst-desc)))

(defn inner-product-bwd-desc
  "Creates a descriptor for the backward phase of the inner product operation,
  for data (3-arguments) weights (5-arguments) updates.

  - The gradient of data computes `diff-src <- f(weights, diff-dst)`:
  `diff-src-desc`: descriptor of the source gradient (input) memory.
  `weights-desc`: descriptor of the weights memory.
  `diff-dst-desc`: descriptor of the destination gradient (output) memory.

  - The gradient of data computes `diff-weights <- f(diff-dst, src)`,
  and `diff-bias <- f(diff-dst, src)`:
  `src-desc`: descriptor of the source (input) memory.
  `diff-weights-desc`: descriptor of the weights gradient memory.
  `diff-bias-desc`: descriptor of the bias gradient memory.
  `diff-dst-desc`: descriptor of the destination gradient (output) memory.
  "
  ([diff-src-desc weights-desc diff-dst-desc]
   (inner-product-backward-data-desc* diff-src-desc weights-desc diff-dst-desc))
  ([src-desc diff-weights-desc diff-bias-desc diff-dst-desc]
   (inner-product-backward-weights-desc* src-desc diff-weights-desc diff-bias-desc diff-dst-desc)))
