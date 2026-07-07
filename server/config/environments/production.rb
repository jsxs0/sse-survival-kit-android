Rage.configure do
  config.server.workers_count = 1
  config.server.port = 3000
  config.logger = Rage::Logger.new(STDOUT)
end
