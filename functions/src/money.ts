export type Allocation = { personId: string; minorUnits: number };

export function equalSplit(total: number, participantIds: string[]): Allocation[] {
  if (!Number.isSafeInteger(total) || total <= 0) throw new Error("invalid-amount");
  const unique = [...new Set(participantIds)].sort();
  if (unique.length === 0 || unique.length !== participantIds.length) throw new Error("invalid-participants");
  const base = Math.floor(total / unique.length);
  const remainder = total % unique.length;
  return unique.map((personId, index) => ({personId, minorUnits: base + (index < remainder ? 1 : 0)}));
}

export function percentSplit(total: number, basisPoints: Record<string, number>): Allocation[] {
  const entries = Object.entries(basisPoints).sort(([a], [b]) => a.localeCompare(b));
  if (entries.length === 0 || entries.some(([, value]) => !Number.isInteger(value) || value < 0) ||
      entries.reduce((sum, [, value]) => sum + value, 0) !== 10_000) throw new Error("invalid-percentages");
  const result = entries.map(([personId, bps]) => {
    const raw = total * bps / 10_000;
    return {personId, minorUnits: Math.floor(raw), fraction: raw - Math.floor(raw)};
  });
  let remainder = total - result.reduce((sum, item) => sum + item.minorUnits, 0);
  result.sort((a, b) => b.fraction - a.fraction || a.personId.localeCompare(b.personId));
  for (const item of result) if (remainder-- > 0) item.minorUnits++;
  return result.map(({personId, minorUnits}) => ({personId, minorUnits}));
}

export function exactSplit(total: number, shares: Record<string, number>): Allocation[] {
  const entries = Object.entries(shares).sort(([a], [b]) => a.localeCompare(b));
  if (entries.length === 0 || entries.some(([, value]) => !Number.isSafeInteger(value) || value < 0) ||
      entries.reduce((sum, [, value]) => sum + value, 0) !== total) throw new Error("invalid-exact-shares");
  return entries.map(([personId, minorUnits]) => ({personId, minorUnits}));
}
