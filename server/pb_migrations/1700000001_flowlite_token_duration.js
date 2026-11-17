/// <reference path="../pb_data/types.d.ts" />
// Devices stay logged in for a year; clients refresh the token on every sync.
migrate((app) => {
  const users = app.findCollectionByNameOrId("users");
  users.authToken.duration = 31536000;
  app.save(users);
}, (app) => {
  const users = app.findCollectionByNameOrId("users");
  users.authToken.duration = 604800;
  app.save(users);
});
