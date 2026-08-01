import {describe, expect, it} from "vitest";
import {equalSplit, exactSplit, percentSplit} from "./money.js";

describe("money allocation", () => {
  it("allocates every cent for equal splits", () => {
    expect(equalSplit(1000, ["c", "a", "b"])).toEqual([
      {personId: "a", minorUnits: 334},
      {personId: "b", minorUnits: 333},
      {personId: "c", minorUnits: 333},
    ]);
  });

  it("uses a stable largest-remainder percentage split", () => {
    const result = percentSplit(101, {a: 3333, b: 3333, c: 3334});
    expect(result.reduce((sum, item) => sum + item.minorUnits, 0)).toBe(101);
    expect(result.find((item) => item.personId === "c")?.minorUnits).toBe(34);
  });

  it("rejects exact shares that do not reconcile", () => {
    expect(() => exactSplit(500, {a: 200, b: 299})).toThrow("invalid-exact-shares");
  });
});
