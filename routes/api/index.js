const router = require('express').Router();
const incomeRoutes = require('./income');
const billsRoutes = require('./bills');

router.use('/income', incomeRoutes);
router.use('/bills', billsRoutes);

module.exports = router;