/// <reference path="../pb_data/types.d.ts" />
// Logins last 30 days instead of a year. Apps refresh the token once a day while they sync,
// so a device in regular use never signs out. A lost device's login expires within 30 days,
// and resetting the password with ./add-user.sh ends every login straight away.
migrate((app) => {
  const users = app.findCollectionByNameOrId("users");
  users.authToken.duration = 2592000;
  app.save(users);
}, (app) => {
  const users = app.findCollectionByNameOrId("users");
  users.authToken.duration = 31536000;
  app.save(users);
});
