(ns isaac.drive.provider-wall-spec
  (:require
    [isaac.drive.provider-wall :as sut]
    [speclj.core :refer :all]))

(describe "provider wall classification"

  (it "classifies 401 http errors as auth unavailability"
    (let [result {:error :api-error :status 401 :message "Unauthorized"}]
      (should= {:unavailable? true
                :retry-after-ms 300000
                :reason :auth
                :provider "grover"}
               (sut/classify result {} "grover"))))

  (it "classifies auth-failed errors as auth unavailability"
    (let [result {:error :auth-failed :status 401}]
      (should= {:unavailable? true
                :retry-after-ms 300000
                :reason :auth
                :provider "xai"}
               (sut/classify result {} "xai"))))

  (it "classifies 429 walls with reason wall"
    (let [result {:error :api-error :status 429 :retry-after 60}]
      (should= {:unavailable? true
                :retry-after-ms 60000
                :reason :wall
                :provider "chatgpt"}
               (sut/classify result {} "chatgpt"))))

  (it "uses configured auth retry-after"
    (let [cfg {:defaults {:provider-auth-retry-ms 120000}}
          result {:error :api-error :status 401 :message "Unauthorized"}]
      (should= 120000 (:retry-after-ms (sut/classify result cfg "grover")))))

  (it "returns nil for genuine tool errors"
    (should= nil (sut/classify {:error :tool-loop-limit} {} "grover"))))