(ns uncomplicate.diamond.internal.cudnn.fully-connected
  (:require [uncomplicate.commons
             [core :refer [Releaseable release let-release with-release Info info]]
             [utils :refer [dragan-says-ex]]]
            [uncomplicate.neanderthal
             [core :refer [axpby! axpy! view dim copy!]]
             [block :refer [cast-prim data-accessor buffer]]
             [math :refer [sqrt pow]]
             [vect-math :refer [sqr! linear-frac! sqrt!]]]
            [uncomplicate.neanderthal.internal.api :refer [flow]]
            [uncomplicate.diamond
             [tensor :as tz
              :refer [Transfer input output connector view-tz revert shape layout
                      TensorDescriptor shape]]
             [dnn :refer [Parameters bias weights transfer-parameters!]]]
            [uncomplicate.diamond.internal.protocols
             :refer [BlueprintProvider DiamondFactoryProvider DiffParameters
                     diff-bias diff-weights Backprop forward backward blueprint
                     create-tensor DiffTransfer diff-input diff-output]]
            [uncomplicate.diamond.internal.cudnn
             [core :refer :all]
             [protocols :refer :all]
             [tensor :refer [cudnn-tensor-desc]]]
            [uncomplicate.diamond.internal.neanderthal.fully-connected
             :refer [->FullyConnectedBlueprint]])
  (:import clojure.lang.IFn
           uncomplicate.diamond.internal.neanderthal.fully_connected.FullyConnectedBlueprint))

(deftype CUDnnSum [cudnn-hdl scale-src src scale-dst dst]
  IFn
  (invoke [this]
    (axpby! scale-src src scale-dst dst)))

(deftype CUDnnSumBlueprint [cudnn-hdl scale-src scale-dst]
  IFn
  (invoke [this src-and-dst]
    (->CUDnnSum cudnn-hdl scale-src src-and-dst scale-dst src-and-dst))
  (invoke [this src dst]
    (->CUDnnSum cudnn-hdl scale-src src scale-dst dst)))

(defn cudnn-sum-blueprint
  ([cudnn-hdl scale]
   (->CUDnnSumBlueprint cudnn-hdl scale 0.0))
  ([cudnn-hdl scale-src scale-dst]
   (->CUDnnSumBlueprint cudnn-hdl scale-src scale-dst)))

;; ================================ Activation =============================================

(deftype CUDnnActivationInference [cudnn-hdl bluep activation-desc a-tz one zero linear]
  Releaseable
  (release [_]
    true)
  Info
  (info [this]
    {:activation (info bluep :activation)
     :a (info a-tz)})
  (info [this info-type]
    (case info-type
      :a (info a-tz)
      (info bluep info-type)))
  Transfer
  (input [_]
    a-tz)
  (output [_]
    a-tz)
  IFn
  (invoke [_]
    (when-not linear
      (activation-forward cudnn-hdl activation-desc
                          one a-tz (buffer a-tz) zero a-tz (buffer a-tz)))
    a-tz))

(deftype CUDnnLinearActivationTraining [cudnn-hdl bluep activation-desc z-tz a-tz one zero]
  Releaseable
  (release [_]
    true)
  Info
  (info [this]
    {:activation (info bluep :activation)
     :z (info z-tz)
     :a (info a-tz)})
  (info [this info-type]
    (case info-type
      :a (info a-tz)
      :z (info z-tz)
      (info bluep info-type)))
  Transfer
  (input [_]
    z-tz)
  (output [_]
    a-tz)
  DiffTransfer
  (diff-input [_]
    a-tz)
  (diff-output [_]
    z-tz)
  IFn
  (invoke [_]
    (copy! z-tz a-tz)
    a-tz)
  Backprop
  (forward [this]
    (copy! z-tz a-tz)
    this)
  (backward [this]
    (copy! a-tz z-tz)
    this))

(defn cudnn-linear-activation-training [cudnn-hdl bluep ad src-tz dst-tz one zero]
  (->CUDnnLinearActivationTraining cudnn-hdl bluep ad src-tz dst-tz one zero))

(deftype CUDnnActivationTraining [cudnn-hdl bluep activation-desc z-tz a-tz da-tz one zero]
  Releaseable
  (release [_]
    (release da-tz))
  Info
  (info [this]
    {:activation (info bluep :activation)
     :z (info z-tz)
     :a (info a-tz)
     :da (info da-tz)})
  (info [this info-type]
    (case info-type
      :a (info a-tz)
      :z (info z-tz)
      :da (info da-tz)
      (info bluep info-type)))
  Transfer
  (input [_]
    z-tz)
  (output [_]
    a-tz)
  DiffTransfer
  (diff-input [_]
    da-tz)
  (diff-output [_]
    z-tz)
  IFn
  (invoke [_]
    (activation-forward cudnn-hdl activation-desc
                        one z-tz (buffer z-tz) zero a-tz (buffer a-tz))
    a-tz)
  Backprop
  (forward [this]
    (activation-forward cudnn-hdl activation-desc
                        one z-tz (buffer z-tz) zero a-tz (buffer a-tz))
    this)
  (backward [this]
    (activation-backward cudnn-hdl activation-desc
                         one a-tz (buffer a-tz) da-tz (buffer da-tz) z-tz (buffer z-tz)
                         zero z-tz (buffer z-tz))
    this))

(defn cudnn-activation-training [cudnn-hdl bluep ad src-tz dst-tz diff-tz one zero]
  (->CUDnnActivationTraining cudnn-hdl bluep ad src-tz dst-tz diff-tz one zero))

(deftype CUDnnActivationBlueprint [fact activ ad]
  Releaseable
  (release [_]
    (release ad))
  Info
  (info [this]
    {:activation activ})
  (info [this info-type]
    (case info-type
      :activation activ
      nil))
  IFn
  (invoke [this src-tz]
    (->CUDnnActivationInference (handle fact) this ad src-tz
                                (cast-prim (data-accessor src-tz) 1.0)
                                (cast-prim (data-accessor src-tz) 0.0)
                                (or (= :linear activ) (= :identity activ))))
  (invoke [this src-tz dst-tz]
    (cond
      (or (= :linear activ) (= :identity activ))
      (->CUDnnLinearActivationTraining (handle fact) this ad src-tz dst-tz
                                       (cast-prim (data-accessor src-tz) 1.0)
                                       (cast-prim (data-accessor dst-tz) 0.0))
      (or (= :sigmoid activ) (:logistic activ))
      (let-release [diff-tz (create-tensor fact dst-tz false)]
        (->CUDnnActivationTraining (handle fact) this ad src-tz dst-tz diff-tz
                                   (cast-prim (data-accessor src-tz) 1.0)
                                   (cast-prim (data-accessor dst-tz) 0.0)))
      :default
      (->CUDnnActivationTraining (handle fact) this ad src-tz dst-tz (view-tz dst-tz)
                                 (cast-prim (data-accessor src-tz) 1.0)
                                 (cast-prim (data-accessor dst-tz) 0.0)))))

(defn cudnn-activ-blueprint [fact activ coef]
  (let-release [ad (activation-descriptor activ true coef)]
    (->CUDnnActivationBlueprint fact activ ad)))

;; ============================= Fully Connected Layer ================================

(extend-type FullyConnectedBlueprint
  DescProvider
  (desc [this]
    (desc (.dst-desc this))))

(defn cudnn-fc-blueprint [fact src-desc dst-desc activ alpha beta]
  (let [dst-shape (shape dst-desc)
        weights-shape [(dst-shape 1) (apply * (rest (shape src-desc)))]]
    (let-release [src-desc (cudnn-tensor-desc (shape src-desc) (data-type src-desc) (layout src-desc))
                  dst-desc (cudnn-tensor-desc [(dst-shape 0) (apply * (rest dst-shape))]
                                              (or (tz/data-type dst-desc) (data-type src-desc))
                                              :nc)
                  bias-desc (cudnn-tensor-desc [(dst-shape 1)] (data-type dst-desc) :x)
                  weights-desc (cudnn-tensor-desc weights-shape (data-type dst-desc) :oi)
                  activ-bluep (cudnn-activ-blueprint fact activ alpha)]
      (->FullyConnectedBlueprint fact activ-bluep src-desc bias-desc weights-desc dst-desc))))

;; ============================= Cost Function ========================================

(deftype UniversalCost [prev-layer
                        connect-output connect-diff
                        a-y y cost]
  Releaseable
  (release [_]
    (release connect-output)
    (release connect-diff))
  Transfer
  (input [this]
    (input connect-output))
  (output [_]
    (output connect-output))
  DiffTransfer
  (diff-input [_]
    (input connect-diff))
  (diff-output [_]
    (output connect-diff))
  Backprop
  (forward [this]
    (connect-output)
    this)
  (backward [this]
    (axpy! -1.0 y a-y)
    (connect-diff)
    (backward prev-layer)
    this)
  IFn
  (invoke [_]
    (connect-output)
    (axpy! -1.0 y a-y)
    (cost a-y)))

(defn cudnn-universal-cost [prev-layer train-tz cost]
  (let [train-desc (desc train-tz)
        output-desc (cudnn-tensor-desc (dims (output prev-layer))
                                       (data-type train-desc) (strides train-desc))]
    (let-release [connect-output (connector (output prev-layer) output-desc)
                  connect-diff (connector output-desc (diff-input prev-layer))]
      (->UniversalCost prev-layer
                       connect-output connect-diff
                       (view (input connect-diff)) (view train-tz)
                       cost))))

(deftype CustomCost [prev-layer
                     connect-output connect-diff
                     a y a-y cost]
  Releaseable
  (release [_]
    (release connect-output)
    (release connect-diff))
  Transfer
  (input [this]
    (input connect-output))
  (output [_]
    (output connect-output))
  DiffTransfer
  (diff-input [_]
    (input connect-diff))
  (diff-output [_]
    (output connect-diff))
  Backprop
  (forward [this]
    (connect-output)
    this)
  (backward [this]
    (copy! a a-y)
    (axpy! -1.0 y a-y)
    (connect-diff)
    (backward prev-layer)
    this)
  IFn
  (invoke [_]
    (connect-output)
    (cost y a)))

(defn cudnn-custom-cost [prev-layer train-tz cost]
  (let [train-desc (desc train-tz)
        output-desc (cudnn-tensor-desc (dims (output prev-layer))
                                       (data-type train-desc) (strides train-desc))]
    (let-release [connect-output (connector (output prev-layer) output-desc)
                  connect-diff (connector output-desc (diff-input prev-layer))]
      (->CustomCost prev-layer
                    connect-output connect-diff
                    (view (output connect-output)) (view train-tz) (view (input connect-diff))
                    cost))))
