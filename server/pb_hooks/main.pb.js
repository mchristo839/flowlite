/// <reference path="../pb_data/types.d.ts" />
// Last write wins on the device clock: reject an update whose updated_at is not newer
// than the stored one. On a tie the first write stays, and clients take the server copy
// on a tie, so two devices saving in the same millisecond still end up with one value.
// Clients treat 409 as "server already has newer data".
onRecordUpdateRequest((e) => {
  const stored = e.record.original().getInt("updated_at");
  const incoming = e.record.getInt("updated_at");
  if (incoming <= stored) {
    throw new ApiError(409, "stale update: server has a newer version", {});
  }
  e.next();
}, "transcripts", "dictionary", "settings");
