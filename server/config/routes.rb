Rage.routes.draw do
  get "telemetry", to: "telemetry#stream"

  get "chaos", to: "chaos#status"
  post "chaos/normal", to: "chaos#normal"
  post "chaos/silent", to: "chaos#silent"
  post "chaos/burst", to: "chaos#burst"
  post "chaos/kill", to: "chaos#kill"
end
