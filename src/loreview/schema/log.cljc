(ns loreview.schema.log
  "Events of the append-only log (SPEC 10.1). Every event is a closed map: the envelope
  (:event/type :event/id :event/at :event/actor) plus its payload. Who did it is always
  :event/actor; payloads carry no separate author."
  (:require [loregate.schema.plan :as plan]
            [loreview.schema.checks :as checks]
            [loreview.schema.tile :as tile]))

(def Actor [:re #"^[a-z]+:\S+$"])                 ; "user:ana", "system:loreview"
(def Version pos-int?)
(def Health [:enum :valid :stale :invalid])
(def Pins [:map-of [:or qualified-keyword? plan/RuleId] plan/Hash])
(def Output [:vector {:min 1} plan/Column])
(def PublishedName [:re #"^[A-Za-z0-9_-]{1,64}$"]) ; also a valid MCP tool name
(def LlmTask [:enum :understand :propose :refine :expectations->checks :repair])

(def Golden
  "A deterministic test case kept at approval (SPEC 9.1): supplied rows per link path, the
  clock, the test user and parameters, and the exact rows they produced."
  [:map {:closed true}
   [:relations [:map-of [:vector qualified-keyword?] [:vector map?]]]
   [:clock [:map {:closed true} [:now inst?] [:zone string?]]]
   [:user [:map [:user/id string?] [:user/attrs map?]]]
   [:params map?]
   [:rows [:vector map?]]])

(def MosaicTile
  [:map {:closed true}
   [:tile/id tile/TileId] [:tile/version Version]
   [:x nat-int?] [:y nat-int?] [:w pos-int?] [:h pos-int?]])

(def payloads
  {:tile/drafted            [[:tile/id tile/TileId] [:prompt string?]]
   :tile/interview-step     [[:tile/id tile/TileId] [:task LlmTask] [:prompt string?] [:output any?]]
   :tile/interview-answered [[:tile/id tile/TileId] [:question any?] [:answer any?]]
   :tile/spec-proposed      [[:tile/id tile/TileId] [:tile/spec tile/Tile] [:diff {:optional true} any?]]
   ;; results: shape fixed in phase 2
   :tile/tested             [[:tile/id tile/TileId] [:tile/version {:optional true} Version]
                             [:plan/id plan/Hash] [:results any?]]
   :tile/approved           [[:tile/id tile/TileId] [:tile/version Version] [:tile/spec tile/Tile]
                             [:plan/id plan/Hash] [:plan/output Output] [:plan/pins Pins]
                             [:tile/lineage string?]
                             ;; the author's user attributes (not data), for deterministic tests (SPEC 9.1)
                             [:test/user [:map-of simple-keyword? any?]]
                             [:test/golden {:optional true} Golden]]
   :tile/plan-replaced      [[:tile/id tile/TileId] [:tile/version Version]
                             [:plan/id plan/Hash] [:plan/output Output] [:plan/pins Pins]
                             [:cause [:vector [:or qualified-keyword? plan/RuleId :keyword]]]
                             [:test/golden {:optional true} Golden]]
   :tile/health-changed     [[:tile/id tile/TileId] [:tile/version Version] [:health Health] [:reason string?]]
   :tile/expectation-failed [[:tile/id tile/TileId] [:tile/version Version] [:check checks/Check]]
   :tile/published          [[:tile/id tile/TileId] [:tile/version Version] [:name PublishedName]]
   :tile/unpublished        [[:tile/id tile/TileId] [:tile/version Version]]
   :tile/retired            [[:tile/id tile/TileId] [:tile/version Version]]
   :tile/repair-proposed    [[:tile/id tile/TileId] [:tile/version Version] [:tile/spec tile/Tile] [:cause any?]]
   :concept/proposed        [[:proposal map?] [:from-tile {:optional true} tile/TileId]]
   :mosaic/saved            [[:mosaic/id qualified-keyword?] [:mosaic/title [:string {:min 1}]]
                             [:mosaic/tiles [:vector MosaicTile]]]})

(def Event
  (into [:multi {:dispatch :event/type}]
        (for [[type fields] payloads]
          [type (into [:map {:closed true}
                       [:event/type [:= type]] [:event/id uuid?] [:event/at inst?] [:event/actor Actor]]
                      fields)])))
