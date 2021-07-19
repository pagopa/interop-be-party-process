#!/bin/bash
SCRIPT_PATH="${BASH_SOURCE[0]}";
if ([ -h "${SCRIPT_PATH}" ]) then
  while([ -h "${SCRIPT_PATH}" ]) do SCRIPT_PATH=`readlink "${SCRIPT_PATH}"`; done
fi
pushd . > /dev/null
cd `dirname ${SCRIPT_PATH}` > /dev/null
SCRIPT_PATH=`pwd`;
popd  > /dev/null

NAMESPACE=$(source $SCRIPT_PATH/config; echo $name)

kubectl create namespace $NAMESPACE

kubectl get secret regcred -n default -o yaml | sed s/"namespace: default"/"namespace: $NAMESPACE"/ | kubectl apply -n $NAMESPACE -f -

kubectl create secret generic vault --from-literal=VAULT_ADDR=$VAULT_ADDR --from-literal=VAULT_TOKEN=$VAULT_TOKEN -n $NAMESPACE

kubectl create secret generic smtp --from-literal=SMTP_USR=$SMTP_USR --from-literal=SMTP_PSW=$SMTP_PSW -n $NAMESPACE

$SCRIPT_PATH/templater.sh $SCRIPT_PATH/deployment.yaml.template -s -f $SCRIPT_PATH/config > $SCRIPT_PATH/deployment.yaml

kubectl create -f $SCRIPT_PATH/deployment.yaml && rm -rf $SCRIPT_PATH/deployment.yaml