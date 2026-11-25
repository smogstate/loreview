(ns loreview.gate.port
  "Loreview's only way to data: Loregate's operations (Loreview SPEC 6), as a port. Every
  call carries the acting user's token; answers are Loregate's data or {:error kw …}.
  Adapters: `loreview.gate.client` (Loregate over HTTP) and `loreview.gate.library` (an
  embedded Loregate). describe joins when the interview needs it.")

(defprotocol Gate
  (compile-query [g token query]
    "→ {:plan/id :plan/output :plan/params} or a compile error.")
  (run-plan [g token plan-id query params]
    "→ Loregate's result {:rows :columns :notes} or a run error. `query` lets Loregate
    rebuild a plan it no longer holds.")
  (run-query [g token query params]
    "Compile and run in one step (draft previews).")
  (publish! [g token plan-id name description]
    "Give a plan a stable name others can run (Loregate needs its publishing role).
    → {:name :plan/id} or {:error …}.")
  (unpublish! [g token plan-id]
    "Remove a plan's name. → {:plan/id} or {:error …}.")
  (plan-status [g token plan-id]
    "→ {:plan/id :plan/output :plan/params :plan/pins :stale? :changed} or {:error :plan/not-found}."))
