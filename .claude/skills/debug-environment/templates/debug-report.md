# Easy-DB-Lab Environment Debug Report

**Date:** [TIMESTAMP]
**Environment:** [ENVIRONMENT_NAME]
**Cluster Type:** [SERVER_TYPE]
**Branch:** [GIT_BRANCH]

---

## 1. Environment Summary

- **Cluster Name:** [NAME]
- **Server Type:** [Cassandra/ClickHouse/OpenSearch/etc]
- **Node Count:** [NUMBER]
- **AWS Region:** [REGION]
- **Instance Type:** [TYPE]
- **Control Node IP:** [IP]

---

## 2. Issue Description

**Problem Statement:**
[Clear description of what's not working]

**Expected Behavior:**
[What should happen]

**Actual Behavior:**
[What is happening instead]

**When Did This Start:**
[Recent change? Fresh deployment? Specific action?]

---

## 3. Environment Validation

### Required Files Check
- [ ] `sshConfig` - [FOUND/MISSING]
- [ ] `kubeconfig` - [FOUND/MISSING]
- [ ] `state.json` - [FOUND/MISSING]

### SSH Connectivity
- [ ] Control node - [SUCCESS/FAILED]
- [ ] Database nodes - [SUCCESS/FAILED]

### Kubernetes Connectivity
- [ ] K8s API server - [ACCESSIBLE/INACCESSIBLE]
- [ ] Cluster info - [AVAILABLE/UNAVAILABLE]

---

## 4. Evidence Collected

### Logs
```
[Relevant log excerpts]
```

### Service Status
```
[Output from systemctl, kubectl get pods, etc.]
```

### Error Messages
```
[Specific error messages encountered]
```

### Recent Events
```
[K8s events, systemd journal entries]
```

---

## 5. Diagnostic Findings

### Symptoms Observed
1. [First symptom]
2. [Second symptom]
3. [Additional symptoms...]

### Tests Performed
1. [Test 1] - [Result]
2. [Test 2] - [Result]
3. [Additional tests...]

### Related Configuration
[Any relevant configuration that may be contributing to the issue]

---

## 6. Root Cause Analysis

**Primary Cause:**
[Your diagnosis of the root cause]

**Contributing Factors:**
- [Factor 1]
- [Factor 2]

**Why This Occurred:**
[Explanation of why this happened]

**Branch-Specific Issues:** [If not on main]
[Details of code/config changes that may have caused this]

---

## 7. Recommended Fix

### Immediate Actions
1. [Step 1]
2. [Step 2]
3. [Additional steps...]

### Implementation

#### Option A: [Primary solution]
**Steps:**
```bash
[Commands to execute]
```

**Rationale:** [Why this is the best solution]

#### Option B: [Alternative solution]
**Steps:**
```bash
[Commands to execute]
```

**Rationale:** [When to use this alternative]

### Code Changes Required
[If fixes require code changes, specify files and changes needed]

### Configuration Changes Required
[If fixes require config changes, specify what needs to be updated]

---

## 8. Verification Steps

After applying the fix, verify with:

1. **Service Health Check**
   ```bash
   [Commands to verify services are running]
   ```

2. **Functional Test**
   ```bash
   [Commands to test functionality]
   ```

3. **Log Verification**
   ```bash
   [Commands to check logs for errors]
   ```

4. **Monitoring Check**
   - [ ] Grafana dashboards show data
   - [ ] No error alerts
   - [ ] Metrics being collected

---

## 9. Prevention

**To prevent this issue in the future:**
1. [Prevention step 1]
2. [Prevention step 2]
3. [Additional prevention measures...]

**Tests to Add:**
[Suggest tests that would catch this issue]

**Documentation Updates:**
[Suggest documentation improvements]

---

## 10. Additional Notes

[Any other relevant information, context, or observations]

---

## References

- [Link to relevant CLAUDE.md file]
- [Link to relevant spec]
- [Link to relevant documentation]
- [Related GitHub issues/PRs]

---

**Next Steps:**
1. [Immediate next step]
2. [Follow-up action]
3. [Long-term improvement]
