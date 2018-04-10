(ns taoensso.tufte-tests
  (:require
   [clojure.test   :as test  :refer [is]]
   [taoensso.tufte :as tufte :refer [profiled profile p]])
  (:import [taoensso.tufte.impl PData PStats]))

(comment
  (remove-ns      'taoensso.tufte-tests)
  (test/run-tests 'taoensso.tufte-tests))

(defn ps? [x] (instance? PStats x))

(defmacro looped "Like `dotimes` but returns final body result."
  [n & body]
  `(let [n# (long ~n)]
     (when (> n# 0)
       (loop [i# 1]
         (let [result# (do ~@body)]
           (if (< i# n#)
             (recur (inc i#))
             result#))))))

(comment (looped 10 (println "x") "x"))

(test/deftest profiled-basics
  (test/testing "Profiled/basics"
    (let [[r ps] (profiled {})]                     (is (= r  nil))  (is (ps? ps)) (is (:clock @ps)) (is (nil? (:stats @ps))))
    (let [[r ps] (profiled {:dynamic? true})]       (is (= r  nil))  (is (ps? ps)) (is (:clock @ps)) (is (nil? (:stats @ps))))
    (let [[r ps] (profiled {}               "foo")] (is (= r "foo")) (is (ps? ps)) (is (:clock @ps)) (is (nil? (:stats @ps))))
    (let [[r ps] (profiled {:dynamic? true} "foo")] (is (= r "foo")) (is (ps? ps)) (is (:clock @ps)) (is (nil? (:stats @ps))))

    (let [[r ps] (profiled {:when false}                "foo")] (is (= r "foo")) (is (nil? ps)))
    (let [[r ps] (profiled {:when false :dynamic? true} "foo")] (is (= r "foo")) (is (nil? ps)))))

(test/deftest capture-basics
  (test/testing "Capture/basics"

    (is (= (p :foo "foo")          "foo"))
    (is (= (p :foo (p :bar "bar")) "bar"))

    (let [[r ps] (profiled {:when false}                (p :foo "foo"))] (is (= r "foo")) (is (nil? ps)))
    (let [[r ps] (profiled {:when false :dynamic? true} (p :foo "foo"))] (is (= r "foo")) (is (nil? ps)))

    (let [[r ps] (profiled {}               (p :foo "foo"))] (is (= r "foo")) (is (ps? ps)) (is (= (get-in @ps [:stats :foo :n]) 1)))
    (let [[r ps] (profiled {:dynamic? true} (p :foo "foo"))] (is (= r "foo")) (is (ps? ps)) (is (= (get-in @ps [:stats :foo :n]) 1)))

    (let [[r ps]
          (profiled {}
            (looped 100 (p :foo "foo"))
            (looped 50  (p :bar "bar"))
            (looped 10  (p :foo "foo")))]

      (is (= r "foo"))
      (is (ps? ps))
      (is (= (get-in @ps [:stats :foo :n]) 110))
      (is (= (get-in @ps [:stats :bar :n]) 50)))

    (let [[r ps]
          (profiled {:dynamic? true}
            (looped 100 (p :foo "foo"))
            (looped  50 (p :bar "bar"))
            (looped  10 (p :foo "foo")))]

      (is (= r "foo"))
      (is (ps? ps))
      (is (= (get-in @ps [:stats :foo :n]) 110))
      (is (= (get-in @ps [:stats :bar :n]) 50)))))

(test/deftest capture-nested
  (test/testing "Capture/nested"

    (let [[r ps] (profiled {:when false}                (p :foo (p :bar (p :baz "baz"))))] (is (= r "baz") (is (nil? ps))))
    (let [[r ps] (profiled {:when false :dynamic? true} (p :foo (p :bar (p :baz "baz"))))] (is (= r "baz") (is (nil? ps))))

    (let [[r ps]
          (profiled {}
            (looped 100 (p :foo (p :bar "bar")))
            (looped  10 (p :bar (p :bar "bar")))
            (looped   5 (p :foo (p :bar (p :foo "foo")))))]

      (is (= r "foo"))
      (is (ps? ps))
      (is (= (get-in @ps [:stats :foo :n]) 110))
      (is (= (get-in @ps [:stats :bar :n]) 125)))

    (let [[r ps]
          (profiled {:dynamic? true}
            (looped 100 (p :foo (p :bar "bar")))
            (looped  10 (p :bar (p :bar "bar")))
            (looped   5 (p :foo (p :bar "bar" (p :foo "foo")))))]

      (is (= r "foo"))
      (is (ps? ps))
      (is (= (get-in @ps [:stats :foo :n]) 110))
      (is (= (get-in @ps [:stats :bar :n]) 125)))))

(test/deftest capture-threaded
  (test/testing "Capture/threaded"

    (let [[r ps]
          (profiled {}
            (future (p :foo))
            (future (p :bar))
            (Thread/sleep 100)
            (p :foo "foo"))]

      (is (= r "foo"))
      (is (ps? ps))
      (is (= (get-in @ps [:stats :foo :n]) 1))
      (is (= (get-in @ps [:stats :bar :n]) nil)))

    (let [[r ps]
          (profiled {:dynamic? true}
            (future (p :foo))
            (future (p :bar))
            (Thread/sleep 100)
            (p :foo "foo"))]

      (is (= r "foo"))
      (is (ps? ps))
      (is (= (get-in @ps [:stats :foo :n]) 2))
      (is (= (get-in @ps [:stats :bar :n]) 1)))))

(test/deftest merging-basics
  (test/testing "Merging/basics"

    ;; Note mixed dynamic/non-dynamic
    (let [[_ ps0] (profiled {:dynamic? true} (looped 100 (p :foo) (p :bar)))
          [_ ps1] (profiled {}               (looped  20 (p :foo)))
          [_ ps2] (profiled {:dynamic? true} (looped  30 (p :baz)))
          ps3 (reduce tufte/merge-pstats [nil ps0 nil nil ps1 ps2 nil])]
      (is (ps? ps3))
      (is (= (get-in @ps3 [:stats :foo :n]) 120))
      (is (= (get-in @ps3 [:stats :bar :n]) 100))
      (is (= (get-in @ps3 [:stats :baz :n])  30)))

    ;; Invert dynamic/non-dynamic
    (let [[_ ps0] (profiled {}               (looped 100 (p :foo) (p :bar)))
          [_ ps1] (profiled {:dynamic? true} (looped  20 (p :foo)))
          [_ ps2] (profiled {}               (looped  30 (p :baz)))
          ps3 (reduce tufte/merge-pstats [nil ps0 nil nil ps1 ps2 nil])]
      (is (ps? ps3))
      (is (= (get-in @ps3 [:stats :foo :n]) 120))
      (is (= (get-in @ps3 [:stats :bar :n]) 100))
      (is (= (get-in @ps3 [:stats :baz :n])  30)))))

(test/deftest compaction-capture
  (test/testing "Compaction/capture"

    (let [[r ps]
          (profiled {:nmax 10}
            (looped 100 (p :foo))
            (looped  50 (p :foo (p :bar (p :baz))))
            (looped   2 (p :qux "qux")))]

      (is (= r "qux"))
      (is (ps? ps))
      (is (= (get-in @ps [:stats :foo :n]) 150))
      (is (= (get-in @ps [:stats :bar :n])  50))
      (is (= (get-in @ps [:stats :baz :n])  50))
      (is (= (get-in @ps [:stats :tufte/compaction :n]) (/ 250 10)))
      (is (= (count (.-acc ^PData (.-pd ^PStats ps))) 2)))

    (let [[r ps]
          (profiled {:nmax 10 :dynamic? true}
            (looped 100 (p :foo))
            (looped  50 (p :foo (p :bar (p :baz))))
            (looped   2 (p :qux "qux")))]

      (is (= r "qux"))
      (is (ps? ps))
      (is (= (get-in @ps [:stats :foo :n]) 150))
      (is (= (get-in @ps [:stats :bar :n])  50))
      (is (= (get-in @ps [:stats :baz :n])  50))
      (is (= (get-in @ps [:stats :tufte/compaction :n]) (/ 250 10)))
      (is (= (count @(.-acc ^PData (.-pd ^PStats ps))) 2)))))

(test/deftest compaction-merge
  (test/testing "Compaction/merge"

    ;; Note mixed dynamic/non-dynamic
    (let [[_ ps0] (profiled {:dynamic? true :nmax 10} (looped 100 (p :foo)) (looped 50 (p :foo (p :bar))))
          [_ ps1] (profiled {}                        (looped  20 (p :foo)))
          [_ ps2] (profiled {:dynamic? true :nmax 5}  (looped  10 (p :bar)))
          ps3 (reduce tufte/merge-pstats [nil ps0 nil nil ps1 ps2 nil])]

      (is (= (get-in @ps0 [:stats :tufte/compaction :n]) 19))
      (is (= (get-in @ps1 [:stats :tufte/compaction :n]) nil))
      (is (= (get-in @ps2 [:stats :tufte/compaction :n]) 1))

      (is (= (get-in @ps3 [:stats :foo :n]) 170))
      (is (= (get-in @ps3 [:stats :bar :n])  60))
      (is (= (get-in @ps3 [:stats :tufte/compaction :n]) 20)) ; Merging does uncounted compaction
      )

    ;; Invert dynamic/non-dynamic
    (let [[_ ps0] (profiled {:nmax 10}       (looped 100 (p :foo)) (looped 50 (p :foo (p :bar))))
          [_ ps1] (profiled {:dynamic? true} (looped  20 (p :foo)))
          [_ ps2] (profiled {:nmax 5}        (looped  10 (p :bar)))
          ps3 (reduce tufte/merge-pstats [nil ps0 nil nil ps1 ps2 nil])]

      (is (= (get-in @ps0 [:stats :tufte/compaction :n]) 19))
      (is (= (get-in @ps1 [:stats :tufte/compaction :n]) nil))
      (is (= (get-in @ps2 [:stats :tufte/compaction :n]) 1))

      (is (= (get-in @ps3 [:stats :foo :n]) 170))
      (is (= (get-in @ps3 [:stats :bar :n])  60))
      (is (= (get-in @ps3 [:stats :tufte/compaction :n]) 20)) ; Merging does uncounted compaction
      )))

(defn add-test-handler! []
  (let [p (promise)]
    (tufte/add-handler! :testing (fn [x] (p x)))
    p))

(test/deftest profile-basics
  (test/testing "Profile/basics"

    (let [pr (add-test-handler!)
          r  (profile {} "foo")
          m  (deref pr 1000 nil)
          ps (:pstats m)]

      (is (= r "foo"))
      (is (ps? ps))
      (is (:clock @ps))
      (is (nil? (:stats @ps))))

    (let [pr (add-test-handler!)
          r  (profile {:dynamic? true} "foo")
          m  (deref pr 1000 nil)
          ps (:pstats m)]

      (is (= r "foo"))
      (is (ps? ps))
      (is (:clock @ps))
      (is (nil? (:stats @ps))))

    (let [pr (add-test-handler!)
          r  (profile {} (future (p :foo)) (Thread/sleep 100) (p :bar "bar"))
          m  (deref pr 1000 nil)
          ps (:pstats m)]

      (is (= r "bar"))
      (is (ps? ps))
      (is (:clock @ps))
      (is (= (get-in @ps [:stats :foo :n]) nil))
      (is (= (get-in @ps [:stats :bar :n]) 1))
      (is (string? @(:pstats-str_ m))))

    (let [pr (add-test-handler!)
          r  (profile {:dynamic? true} (future (p :foo)) (Thread/sleep 100) (p :bar "bar"))
          m  (deref pr 1000 nil)
          ps (:pstats m)]

      (is (= r "bar"))
      (is (ps? ps))
      (is (:clock @ps))
      (is (= (get-in @ps [:stats :foo :n]) 1))
      (is (= (get-in @ps [:stats :bar :n]) 1))
      (is (string? @(:pstats-str_ m))))

    (tufte/remove-handler! :testing)))
