#!/bin/bash

log "INFO" "Obtaining and setting TLS secrets for HTTP exposed service"

getCert "userland" "$TENANT_NAME" "$TENANT_NAME" "JKS" "/root/kms/secrets" || exit $?

### Get keystore password
JKS_PASSWORD=${TENANT_NORM}_KEYSTORE_PASS
export XD_TLS_PASSWORD=${!JKS_PASSWORD}
export XD_TLS_JKS_NAME="/root/kms/$TENANT_NAME.jks"

mv /root/kms/secrets/$TENANT_NAME.jks /root/kms/secrets/keyStore.jks

if [[ ${#XD_TLS_PASSWORD} -lt 6 ]]; then
    log "ERROR" "JKS Password for TLS must have at least 6 characters"
    exit 1
fi

#Update driver-reference.conf
echo ${XD_TLS_PASSWORD} > /root/kms/secrets/keyStore

log "INFO" "TLS secrets for HTTP exposed service: OK"
