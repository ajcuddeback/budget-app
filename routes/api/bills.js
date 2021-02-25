const router = require('express').Router();
const { Bills } = require('../../models');

router.get('/', (req, res) => {
    Bills.findAll({

    })
        .then(dbBillsData => {
            if (!dbBillsData) {
                res.status(404).json({ message: 'There is no Bills data' })
            }

            res.json(dbBillsData);
        })
        .catch(err => {
            console.log(err);
            res.status(500).json(err)
        })
});

router.post('/', (req, res) => {
    Bills.create({
        name: req.body.name,
        amount: req.body.amount,
        user_id: req.session.user_id
    })
        .then(dbBillsData => res.json(dbBillsData))
        .catch(err => {
            console.log(err.message);
            res.status(500).json(err)
        })
});

router.delete('/:id', (req, res) => {
    Bills.destroy({
        where: {
            id: req.params.id
        }
    })
});

module.exports = router;