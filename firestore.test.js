const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const OWNER_UID = "owner_uid_1";
const STAFF_UID = "staff_uid_2";
const STRANGER_UID = "stranger_uid_3";
const STORE_ID = "store_abc_1";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

async function seedStoreAndOwner() {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminDb = ctx.firestore();
    const now = new Date(Date.now() - 10000);
    await adminDb.collection("users").doc(OWNER_UID).set({
      email: "owner@shop.com",
      storeId: STORE_ID,
      role: "owner",
      isSimpleMode: false,
      createdAt: now,
      updatedAt: now,
    });
    await adminDb.collection("stores").doc(STORE_ID).set({
      name: "Sakibul Traders",
      phone: "01712345678",
      address: "Dhaka",
      shopType: "general",
      productCategories: ["Grocery"],
      ownerId: OWNER_UID,
      memberUids: [OWNER_UID],
      members: [{ userId: OWNER_UID, role: "owner", status: "active" }],
      createdAt: now,
      updatedAt: now,
    });
    await adminDb
      .collection("stores")
      .doc(STORE_ID)
      .collection("members")
      .doc(OWNER_UID)
      .set({
        userId: OWNER_UID,
        displayName: "Owner",
        email: "owner@shop.com",
        role: "owner",
        roleLabel: "Owner",
        permissionLevel: "Full",
        permissions: { sell: true },
        status: "Active",
        joinedTime: Date.now() - 10000,
        invitedBy: "",
        inviteCode: "",
        updatedAt: now,
      });
  });
}

test("Unauthenticated user: denied across users, stores, invites", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(OWNER_UID).get());
  await assertFails(
    unauthDb.collection("users").doc(OWNER_UID).set({
      email: "x@y.com",
      storeId: "",
      role: "owner",
      isSimpleMode: false,
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(unauthDb.collection("invites").doc("K7M9P2XQ").get());
  await assertFails(unauthDb.collection("invites").where("storeId", "==", STORE_ID).get());
  await assertFails(unauthDb.collection("stores").doc(STORE_ID).get());
});

test("Google Sign-In onboarding: signed-in user reads non-existent profile, saves initial profile, creates store", async () => {
  const ownerDb = testEnv.authenticatedContext(OWNER_UID, { email: "owner@shop.com" }).firestore();
  const now = new Date(Date.now() - 1000);

  // 1. AuthViewModel: getUserProfileOnce when profile does not exist yet
  const initialSnap = await assertSucceeds(ownerDb.collection("users").doc(OWNER_UID).get());
  assert.equal(initialSnap.exists, false);

  // 2. AuthViewModel -> StoreRepository.saveUserProfile on initial Google sign-in
  await assertSucceeds(
    ownerDb.collection("users").doc(OWNER_UID).set(
      {
        email: "owner@shop.com",
        storeId: "",
        role: "owner",
        isSimpleMode: false,
        createdAt: now,
        updatedAt: now,
      },
      { merge: true }
    )
  );

  // Also ensure updateUserSimpleMode merge write works cleanly
  await assertSucceeds(
    ownerDb.collection("users").doc(OWNER_UID).set(
      {
        isSimpleMode: true,
        updatedAt: new Date(Date.now() - 500),
      },
      { merge: true }
    )
  );

  // Cross-user profile read/write/list must fail
  const strangerDb = testEnv.authenticatedContext(STRANGER_UID).firestore();
  await assertFails(strangerDb.collection("users").doc(OWNER_UID).get());
  await assertFails(strangerDb.collection("users").get());
  await assertFails(
    ownerDb.collection("users").doc(OWNER_UID).set(
      {
        email: "owner@shop.com",
        storeId: "other_nonexistent_store",
        role: "owner",
        isSimpleMode: false,
        updatedAt: now,
      },
      { merge: true }
    )
  );

  // 3. StoreOnboardingScreen -> StoreRepository.createStore
  const storeRef = ownerDb.collection("stores").doc(STORE_ID);
  await assertSucceeds(
    storeRef.set({
      name: "Sakibul Traders",
      phone: "01712345678",
      address: "Dhaka",
      shopType: "general",
      productCategories: ["Grocery"],
      ownerId: OWNER_UID,
      memberUids: [OWNER_UID],
      members: [{ userId: OWNER_UID, role: "owner", status: "active" }],
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    ownerDb.collection("users").doc(OWNER_UID).set(
      {
        email: "owner@shop.com",
        storeId: STORE_ID,
        role: "owner",
        updatedAt: new Date(Date.now() - 500),
      },
      { merge: true }
    )
  );

  await assertSucceeds(
    storeRef.collection("members").doc(OWNER_UID).set({
      userId: OWNER_UID,
      displayName: "Owner",
      email: "owner@shop.com",
      role: "owner",
      roleLabel: "Owner",
      permissionLevel: "Full",
      permissions: { sell: true },
      status: "Active",
      joinedTime: Date.now(),
      invitedBy: "",
      inviteCode: "",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  await assertSucceeds(
    storeRef.collection("meta").doc("counters").set({ invoiceSeq: 100 }, { merge: true })
  );
});

test("Staff screen: owner creates, lists, and cancels invite; non-owner cannot create or list invites", async () => {
  await seedStoreAndOwner();

  const ownerDb = testEnv.authenticatedContext(OWNER_UID).firestore();
  const strangerDb = testEnv.authenticatedContext(STRANGER_UID).firestore();
  const code = "K7M9P2XQ";
  const createdMs = Date.now();
  const expiresMs = createdMs + 24 * 60 * 60 * 1000;

  // Owner creates invite mirroring StoreRepository.createStaffInvite(StoreInvite)
  await assertSucceeds(
    ownerDb.collection("invites").doc(code).set({
      code,
      storeId: STORE_ID,
      storeName: "Sakibul Traders",
      staffName: "Rahim",
      roleLabel: "Sales",
      permissionLevel: "Limited",
      permissions: { sell: true, view_stock: true },
      createdByUid: OWNER_UID,
      createdByName: "Owner",
      status: "active",
      createdAt: createdMs,
      expiresAt: expiresMs,
      usedByUid: "",
      usedByEmail: "",
      usedAt: 0,
    })
  );

  // Owner query mirroring StoreRepository.observeInvites(storeId)
  const listSnap = await assertSucceeds(
    ownerDb.collection("invites").where("storeId", "==", STORE_ID).get()
  );
  assert.equal(listSnap.size, 1);

  // Stranger cannot list invites for any store or unfiltered, and cannot create forged invite
  await assertFails(strangerDb.collection("invites").where("storeId", "==", STORE_ID).get());
  await assertFails(strangerDb.collection("invites").get());
  await assertFails(
    strangerDb.collection("invites").doc("ABCD2345").set({
      code: "ABCD2345",
      storeId: STORE_ID,
      storeName: "Sakibul Traders",
      staffName: "Hacker",
      roleLabel: "Sales",
      permissionLevel: "Full",
      permissions: { sell: true },
      createdByUid: STRANGER_UID,
      createdByName: "Hacker",
      status: "active",
      createdAt: createdMs,
      expiresAt: expiresMs,
      usedByUid: "",
      usedByEmail: "",
      usedAt: 0,
    })
  );

  // Owner cancels invite mirroring StoreRepository.cancelInvite
  await assertSucceeds(
    ownerDb.collection("invites").doc(code).update({
      status: "cancelled",
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Staff onboarding: invited user joins store atomically via transaction; stranger cannot self-join without invite", async () => {
  await seedStoreAndOwner();
  const ownerDb = testEnv.authenticatedContext(OWNER_UID).firestore();
  const staffDb = testEnv.authenticatedContext(STAFF_UID, { email: "rahim@shop.com" }).firestore();
  const strangerDb = testEnv.authenticatedContext(STRANGER_UID).firestore();

  const code = "M4N8Q3YZ";
  const createdMs = Date.now();
  const expiresMs = createdMs + 24 * 60 * 60 * 1000;

  await assertSucceeds(
    ownerDb.collection("invites").doc(code).set({
      code,
      storeId: STORE_ID,
      storeName: "Sakibul Traders",
      staffName: "Rahim",
      roleLabel: "Sales",
      permissionLevel: "Limited",
      permissions: { sell: true, view_stock: true },
      createdByUid: OWNER_UID,
      createdByName: "Owner",
      status: "active",
      createdAt: createdMs,
      expiresAt: expiresMs,
      usedByUid: "",
      usedByEmail: "",
      usedAt: 0,
    })
  );

  // Stranger cannot directly add themselves to store.memberUids without valid invite transaction
  await assertFails(
    strangerDb.collection("stores").doc(STORE_ID).update({
      memberUids: [OWNER_UID, STRANGER_UID],
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Staff executes transaction mirroring StoreRepository.joinStoreWithInvite
  await assertSucceeds(
    staffDb.runTransaction(async (tx) => {
      const inviteRef = staffDb.collection("invites").doc(code);
      const storeRef = staffDb.collection("stores").doc(STORE_ID);
      const memberRef = storeRef.collection("members").doc(STAFF_UID);
      const userRef = staffDb.collection("users").doc(STAFF_UID);

      const invSnap = await tx.get(inviteRef);
      assert.equal(invSnap.exists, true);
      const storeDataUpdate = {
        memberUids: [OWNER_UID, STAFF_UID],
        updatedAt: new Date(Date.now() - 500),
      };

      tx.update(inviteRef, {
        status: "used",
        usedByUid: STAFF_UID,
        usedByEmail: "rahim@shop.com",
        usedAt: Date.now(),
        updatedAt: new Date(Date.now() - 500),
      });

      tx.set(memberRef, {
        userId: STAFF_UID,
        displayName: "Rahim",
        email: "rahim@shop.com",
        role: "sales",
        roleLabel: "Sales",
        permissionLevel: "Limited",
        permissions: { sell: true, view_stock: true },
        status: "Active",
        joinedTime: Date.now(),
        invitedBy: OWNER_UID,
        inviteCode: code,
      });

      tx.update(storeRef, storeDataUpdate);

      tx.set(
        userRef,
        {
          email: "rahim@shop.com",
          storeId: STORE_ID,
          role: "Sales",
          updatedAt: new Date(Date.now() - 500),
        },
        { merge: true }
      );
    })
  );

  // Staff can now read store and products, but CANNOT read owner-only private/aiKey or list store invites
  await assertSucceeds(staffDb.collection("stores").doc(STORE_ID).get());
  await assertSucceeds(staffDb.collection("stores").doc(STORE_ID).collection("products").get());
  await assertFails(
    staffDb.collection("stores").doc(STORE_ID).collection("private").doc("aiKey").get()
  );
  await assertFails(staffDb.collection("invites").where("storeId", "==", STORE_ID).get());
});
