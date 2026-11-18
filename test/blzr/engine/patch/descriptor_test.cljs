(ns blzr.engine.patch.descriptor-test
  (:require [cljs.test :refer [deftest is testing]]
            [blzr.engine.patch.descriptor :as desc]))

(deftest to-graph-position-defaulting
  (testing "missing position key defaults to origin"
    (let [descriptor {:structure {:nodes {:n1 {:type :osc}} :edges []}
                       :params {}
                       :positions {}
                       :sizes {}}
          patch (desc/to-graph descriptor)]
      (is (= {:x 0 :y 0} (get-in patch [:flow :nodes :n1 :position])))))

  (testing "explicit nil position value also defaults to origin"
    (let [descriptor {:structure {:nodes {:n1 {:type :osc}} :edges []}
                       :params {}
                       :positions {:n1 nil}
                       :sizes {}}
          patch (desc/to-graph descriptor)]
      (is (= {:x 0 :y 0} (get-in patch [:flow :nodes :n1 :position])))))

  (testing "valid stored position is preserved"
    (let [descriptor {:structure {:nodes {:n1 {:type :osc}} :edges []}
                       :params {}
                       :positions {:n1 {:x 200 :y 400}}
                       :sizes {}}
          patch (desc/to-graph descriptor)]
      (is (= {:x 200 :y 400} (get-in patch [:flow :nodes :n1 :position]))))))
